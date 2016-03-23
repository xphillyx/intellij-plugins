package training.learn;

import com.intellij.ide.RecentProjectsManager;
import com.intellij.ide.scratch.ScratchFileService;
import com.intellij.ide.scratch.ScratchRootType;
import com.intellij.lang.Language;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.*;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.projectRoots.*;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import training.editor.eduUI.EduPanel;
import training.editor.eduUI.MainEduPanel;
import training.learn.dialogs.SdkModuleProblemDialog;
import training.learn.dialogs.SdkProjectProblemDialog;
import training.learn.exceptons.*;
import training.learn.log.GlobalLessonLog;
import training.ui.LearnToolWindowFactory;
import training.util.generateModuleXml;
import training.util.MyClassLoader;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Scanner;
import java.util.concurrent.ExecutionException;

/**
 * Created by karashevich on 11/03/15.
 */
@State(
        name = "TrainingPluginModules",
        storages = {
                @Storage(
                        file = StoragePathMacros.APP_CONFIG + "/trainingPlugin.xml"
                )
        }
)
public class CourseManager implements PersistentStateComponent<CourseManager.State> {

    private Project eduProject;
    private EduPanel myEduPanel;
    final public static String EDU_PROJECT_NAME = "EduProject";
    private MainEduPanel mainEduPanel;

    CourseManager() {
        if (myState.modules == null || myState.modules.size() == 0) try {
            initModules();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private HashMap<Module, VirtualFile> mapModuleVirtualFile = new HashMap<Module, VirtualFile>();
    private State myState = new State();

    public static CourseManager getInstance() {
        return ServiceManager.getService(CourseManager.class);
    }

    public void initModules() throws JDOMException, IOException, URISyntaxException, BadModuleException, BadLessonException {
        Element modulesRoot = Module.getRootFromPath(generateModuleXml.MODULE_ALLMODULE_FILENAME);
        for (Element element : modulesRoot.getChildren()) {
            if (element.getName().equals(generateModuleXml.MODULE_TYPE_ATTR)) {
                String moduleFilename = element.getAttribute(generateModuleXml.MODULE_NAME_ATTR).getValue();
                final Module module = Module.initModule(moduleFilename);
                addModule(module);
            }
        }
    }


    @Nullable
    public Module getModuleById(String id) {
        final Module[] modules = getModules();
        if (modules == null || modules.length == 0) return null;

        for (Module module : modules) {
            if (module.getId().toUpperCase().equals(id.toUpperCase())) return module;
        }
        return null;
    }

    public void registerVirtualFile(Module module, VirtualFile virtualFile) {
        mapModuleVirtualFile.put(module, virtualFile);
    }

    public boolean isVirtualFileRegistered(VirtualFile virtualFile) {
        return mapModuleVirtualFile.containsValue(virtualFile);
    }

    public void unregisterVirtaulFile(VirtualFile virtualFile) {
        if (!mapModuleVirtualFile.containsValue(virtualFile)) return;
        for (Module module : mapModuleVirtualFile.keySet()) {
            if (mapModuleVirtualFile.get(module).equals(virtualFile)) {
                mapModuleVirtualFile.remove(module);
                return;
            }
        }
    }

    public void unregisterModule(Module module) {
        mapModuleVirtualFile.remove(module);
    }


    public synchronized void openLesson(Project project, final @Nullable Lesson lesson) throws BadModuleException, BadLessonException, IOException, FontFormatException, InterruptedException, ExecutionException, LessonIsOpenedException {

        try {

            assert lesson != null;
            checkEnvironment(project, lesson.getModule());

            if (lesson.isOpen()) throw new LessonIsOpenedException(lesson.getName() + " is opened");

            //If lesson doesn't have parent module
            if (lesson.getModule() == null)
                throw new BadLessonException("Unable to open lesson without specified module");
            final Project myProject = project;
            final String scratchFileName = "Learning...";
            final VirtualFile vf = ApplicationManager.getApplication().runWriteAction(new Computable<VirtualFile>() {
                @Override
                public VirtualFile compute() {
                    try {
                        if (lesson.getModule().moduleType == Module.ModuleType.SCRATCH) {
                            return getScratchFile(myProject, lesson, scratchFileName);
                        } else {
                            if (!initEduProject(myProject)) return null;
                            return getFileInEduProject(lesson);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                        return null;
                    }
                }
            });
            if (vf == null) return; //if user aborts opening lesson in EduProject or Virtual File couldn't be computed
            if (lesson.getModule().moduleType != Module.ModuleType.SCRATCH) project = eduProject;

            //open next lesson if current is passed
            final Project currentProject = project;

            lesson.onStart();

            lesson.addLessonListener(new LessonListenerAdapter() {
                @Override
                public void lessonNext(Lesson lesson) throws BadLessonException, ExecutionException, IOException, FontFormatException, InterruptedException, BadModuleException, LessonIsOpenedException {
                    if (lesson.getModule() == null) return;

                    if (lesson.getModule().hasNotPassedLesson()) {
                        Lesson nextLesson = lesson.getModule().giveNotPassedAndNotOpenedLesson();
                        if (nextLesson == null)
                            throw new BadLessonException("Unable to obtain not passed and not opened lessons");
                        openLesson(currentProject, nextLesson);
                    }
                }
            });

            final String target;
            if (lesson.getTargetPath() != null) {
                InputStream is = MyClassLoader.getInstance().getResourceAsStream(lesson.getModule().getAnswersPath() + lesson.getTargetPath());
                if (is == null) throw new IOException("Unable to get answer for \"" + lesson.getName() + "\" lesson");
                target = new Scanner(is).useDelimiter("\\Z").next();
            } else {
                target = null;
            }


            //Dispose balloon while scratch file is closing. InfoPanel still exists.
            project.getMessageBus().connect(project).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
                @Override
                public void fileOpened(FileEditorManager source, VirtualFile file) {
                }

                @Override
                public void fileClosed(FileEditorManager source, VirtualFile file) {
                    lesson.close();
                }

                @Override
                public void selectionChanged(FileEditorManagerEvent event) {

                }
            });

            //to start any lesson we need to do 4 steps:
            //1. open editor
            Editor editor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, vf), true);

            //2. create LessonManager
            LessonManager lessonManager = new LessonManager(lesson, editor);

            //3. update tool window
//            updateToolWindow(project);
            CourseManager.getInstance().getEduPanel().clear();


            //4. Process lesson
            LessonProcessor.process(project, lesson, editor, target);

        } catch (NoSdkException | InvalidSdkException noSdkException){
            showSdkProblemDialog(project, noSdkException.getMessage());
        } catch (NoJavaModuleException noJavaModuleException){
            showModuleProblemDialog(project);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private VirtualFile getFileInEduProject(Lesson lesson) throws IOException {

        final VirtualFile sourceRootFile = ProjectRootManager.getInstance(eduProject).getContentSourceRoots()[0];
        String moduleFileName = "Test.java";
        if (lesson.getModule() != null) moduleFileName = lesson.getModule().getName() + ".java";


        VirtualFile moduleVirtualFile = sourceRootFile.findChild(moduleFileName);
        if (moduleVirtualFile == null) {
            moduleVirtualFile = sourceRootFile.createChildData(this, moduleFileName);
        }

        registerVirtualFile(lesson.getModule(), moduleVirtualFile);
        return moduleVirtualFile;
    }

    private boolean initEduProject(Project projectToClose) {
        Project myEduProject = null;

        //if projectToClose is open
        final Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
        for (Project openProject : openProjects) {
            final String name = openProject.getName();
            if (name.equals(EDU_PROJECT_NAME)) {
                myEduProject = openProject;
                if (ApplicationManager.getApplication().isUnitTestMode()) return true;
            }
        }
         if (myEduProject == null || myEduProject.getProjectFile() == null) {

            if(!ApplicationManager.getApplication().isUnitTestMode()) if (!NewEduProjectUtil.showDialogOpenEduProject(projectToClose)) return false; //if user abort to open lesson in a new Project
            if(myState.eduProjectPath != null) {
                try {
                    myEduProject = ProjectManager.getInstance().loadAndOpenProject(myState.eduProjectPath);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else {

                try {
                    final Sdk newJdk = getJavaSdkInWA();
//                    final Sdk sdk = SdkConfigurationUtil.findOrCreateSdk(null, javaSdk);
                    myEduProject = NewEduProjectUtil.createEduProject(EDU_PROJECT_NAME, projectToClose, newJdk);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        eduProject = myEduProject;
        if (ApplicationManager.getApplication().isUnitTestMode()) return true;

        assert eduProject != null;
        assert eduProject.getProjectFile() != null;
        assert eduProject.getProjectFile().getParent() != null;
        assert eduProject.getProjectFile().getParent().getParent() != null;

        myState.eduProjectPath = eduProject.getBasePath();
        //Hide EduProject from Recent projects
        RecentProjectsManager.getInstance().removePath(eduProject.getPresentableUrl());
        return true;
    }

    @NotNull
    public Sdk getJavaSdkInWA() {
        final Sdk newJdk;
        if (ApplicationManager.getApplication().isUnitTestMode()) {
            newJdk = ApplicationManager.getApplication().runWriteAction(new Computable<Sdk>() {
                @Override
                public Sdk compute() {
                    return getJavaSdk();
                };
            });
        } else {
            newJdk = getJavaSdk();
        }
        return newJdk;
    }

    @NotNull
    public Sdk getJavaSdk() {
        JavaSdk javaSdk = JavaSdk.getInstance();
        final String suggestedHomePath = javaSdk.suggestHomePath();
        final String versionString = javaSdk.getVersionString(suggestedHomePath);
        final Sdk newJdk = javaSdk.createJdk(javaSdk.getVersion(versionString).name(), suggestedHomePath);

        final Sdk foundJdk = ProjectJdkTable.getInstance().findJdk(newJdk.getName(), newJdk.getSdkType().getName());
        if (foundJdk == null) {
            ProjectJdkTable.getInstance().addJdk(newJdk);
        }
        return newJdk;
    }

    @Nullable
    public Project getEduProject(){
        if (eduProject == null  || eduProject.isDisposed()) {
            if (initEduProject(getCurrentProject()))
                return eduProject;
            else
                return null;
        } else {
            return eduProject;
        }
    }

    @Nullable
    public Project getCurrentProject(){
        final IdeFrame lastFocusedFrame = IdeFocusManager.getGlobalInstance().getLastFocusedFrame();
        if (lastFocusedFrame == null) return null;
        return lastFocusedFrame.getProject();
    }

    @NotNull
    private VirtualFile getScratchFile(@NotNull final Project project, @Nullable Lesson lesson, @NotNull final String filename) throws IOException {
        VirtualFile vf = null;
        assert lesson != null;
        assert lesson.getModule() != null;
        if (mapModuleVirtualFile.containsKey(lesson.getModule()))
            vf = mapModuleVirtualFile.get(lesson.getModule());
        if (vf == null || !vf.isValid()) {
            //while module info is not stored

            //find file if it is existed
            vf = ScratchFileService.getInstance().findFile(ScratchRootType.getInstance(), filename, ScratchFileService.Option.existing_only);
            if (vf != null) {
                FileEditorManager.getInstance(project).closeFile(vf);
                ScratchFileService.getInstance().getScratchesMapping().setMapping(vf, Language.findLanguageByID("JAVA"));
            }

            if (vf == null || !vf.isValid()) {
                vf = ScratchRootType.getInstance().createScratchFile(project, filename, Language.findLanguageByID("JAVA"), "");
                final VirtualFile finalVf = vf;
                if (!vf.getName().equals(filename)) {
                    ApplicationManager.getApplication().runWriteAction(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                finalVf.rename(project, filename);
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    });
                }
            }
            registerVirtualFile(lesson.getModule(), vf);
        }
        return vf;
    }

    /**
     * checking environment to start education plugin. Checking SDK.
     *
     * @param project where lesson should be started
     * @param module  education module
     * @throws OldJdkException     - if project JDK version is not enough for this module
     * @throws InvalidSdkException - if project SDK is not suitable for module
     */
    public void checkEnvironment(Project project, @Nullable Module module) throws OldJdkException, InvalidSdkException, NoSdkException, NoJavaModuleException {

        if (module == null) return;

        final Sdk projectJdk = ProjectRootManager.getInstance(project).getProjectSdk();
        if (projectJdk == null) throw new NoSdkException();

        final SdkTypeId sdkType = projectJdk.getSdkType();
        if (module.getSdkType() == Module.ModuleSdkType.JAVA) {
            if (sdkType instanceof JavaSdk) {
                final JavaSdkVersion version = ((JavaSdk) sdkType).getVersion(projectJdk);
                if (version != null) {
                    if (!version.isAtLeast(JavaSdkVersion.JDK_1_6)) throw new OldJdkException(JavaSdkVersion.JDK_1_6);
                    try {
                        checkJavaModule(project);
                    } catch (NoJavaModuleException e) {
                        throw e;
                    }
                }
            } else if (sdkType.getName().equals("IDEA JDK")) {
                try {
                    checkJavaModule(project);
                } catch (NoJavaModuleException e) {
                    throw e;
                }
            } else {
                throw new InvalidSdkException("Please use at least JDK 1.6 or IDEA SDK with corresponding JDK");
            }
        }
    }

    private void checkJavaModule(Project project) throws NoJavaModuleException {

        if (ModuleManager.getInstance(project).getModules().length == 0) {
            throw new NoJavaModuleException();
        }

    }

    public void showSdkProblemDialog(Project project, String sdkMessage) {
//        final SdkProblemDialog dialog = new SdkProblemDialog(project, "at least JDK 1.6 or IDEA SDK with corresponding JDK");
        final SdkProjectProblemDialog dialog = new SdkProjectProblemDialog(project, sdkMessage);
        dialog.show();
    }

    public void showModuleProblemDialog(Project project){
        final SdkModuleProblemDialog dialog = new SdkModuleProblemDialog(project);
        dialog.show();
    }

    @Nullable
    public Lesson findLesson(String lessonName) {
        if (getModules() == null) return null;
        for (Module module : getModules()) {
            for (Lesson lesson : module.getLessons()) {
                if (lesson.getName() != null)
                    if (lesson.getName().toUpperCase().equals(lessonName.toUpperCase()))
                        return lesson;
            }
        }
        return null;
    }

    public void setEduPanel(EduPanel eduPanel) {
        myEduPanel = eduPanel;
    }

    public EduPanel getEduPanel(){
        return myEduPanel;
    }

    public void setMainEduPanel(MainEduPanel mainEduPanel) {
        this.mainEduPanel = mainEduPanel;
    }

    public MainEduPanel getMainEduPanel() {
        return mainEduPanel;
    }



    static class State {
        public final ArrayList<Module> modules = new ArrayList<Module>();
        public String eduProjectPath;
        public GlobalLessonLog globalLessonLog = new GlobalLessonLog();

        public State() {
        }


    }


    public void addModule(Module module) {
        myState.modules.add(module);
    }

    @Nullable
    public Module[] getModules() {
        if (myState == null) return null;
        if (myState.modules == null) return null;

        return myState.modules.toArray(new Module[myState.modules.size()]);
    }

    public GlobalLessonLog getGlobalLessonLog(){
        return myState.globalLessonLog;
    }

    @Override
    public State getState() {
        return myState;
    }

    @Override
    public void loadState(State state) {
        myState.eduProjectPath = null;
        myState.globalLessonLog = state.globalLessonLog;

        if (state.modules == null || state.modules.size() == 0) {
            try {
                initModules();
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {

            for (Module module : myState.modules) {
                if (state.modules.contains(module)) {
                    final Module moduleFromPersistentState = state.modules.get(state.modules.indexOf(module));
                    for (Lesson lesson : module.getLessons()) {
                        if (moduleFromPersistentState.getLessons().contains(lesson)) {
                            final Lesson lessonFromPersistentState = moduleFromPersistentState.getLessons().get(moduleFromPersistentState.getLessons().indexOf(lesson));
                            lesson.setPassed(lessonFromPersistentState.getPassed());
                        }
                    }
                }
            }
        }
    }

    public void updateToolWindow(@NotNull final Project project){
        final ToolWindowManager windowManager = ToolWindowManager.getInstance(project);
        String learnToolWindow = LearnToolWindowFactory.LEARN_TOOL_WINDOW;
        windowManager.getToolWindow(learnToolWindow).getContentManager().removeAllContents(false);

        LearnToolWindowFactory factory = new LearnToolWindowFactory();
        factory.createToolWindowContent(project, windowManager.getToolWindow(learnToolWindow));
    }

    public void setLessonView(Project project){
        final ToolWindowManager windowManager = ToolWindowManager.getInstance(project);
        String learnToolWindow = LearnToolWindowFactory.LEARN_TOOL_WINDOW;
        ToolWindow toolWindow = windowManager.getToolWindow(learnToolWindow);
        JComponent toolWindowComponent = toolWindow.getComponent();
        toolWindowComponent.removeAll();
        toolWindowComponent.add(getEduPanel());
        toolWindowComponent.revalidate();
        toolWindowComponent.repaint();
    }

    public void setModulesView(Project project){
        final ToolWindowManager windowManager = ToolWindowManager.getInstance(project);
        String learnToolWindow = LearnToolWindowFactory.LEARN_TOOL_WINDOW;
        ToolWindow toolWindow = windowManager.getToolWindow(learnToolWindow);
        JComponent toolWindowComponent = toolWindow.getComponent();
        toolWindowComponent.removeAll();
        toolWindowComponent.add(getMainEduPanel());
        toolWindowComponent.revalidate();
        toolWindowComponent.repaint();
    }

    /**
     *
     * @param currentLesson
     * @return null if lesson has no module or it is only one lesson in module
     */
    public @Nullable Lesson giveNextLesson(Lesson currentLesson){
        Module module = currentLesson.getModule();
        ArrayList<Lesson> lessons = module.getLessons();
        int size = lessons.size();
        if (module == null || size == 1) return  null;

        for (int i = 0; i < size; i++) {
            if (lessons.get(i).equals(currentLesson)) {
                if (i + 1 < size) return lessons.get(i + 1);
                else break;
            }
        }
        return null;
    }

    @Nullable
    public Module giveNextModule(Lesson currentLesson) {
        Module module = currentLesson.getModule();
        Module[] modules = CourseManager.getInstance().getModules();
        if (modules == null) return null;
        int size = modules.length;
        if (size == 1) return null;

        for (int i = 0; i < size; i++) {
            if (modules[i].equals(module)) {
                if (i + 1 < size) return modules[i + 1];
                else break;
            }
        }
        return null;
    }


}