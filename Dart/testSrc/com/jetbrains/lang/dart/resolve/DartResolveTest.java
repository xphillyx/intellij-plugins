package com.jetbrains.lang.dart.resolve;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.containers.hash.LinkedHashMap;
import com.jetbrains.lang.dart.DartCodeInsightFixtureTestCase;
import com.jetbrains.lang.dart.psi.DartComponentName;
import com.jetbrains.lang.dart.util.DartTestUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

public class DartResolveTest extends DartCodeInsightFixtureTestCase {

  private void doTest() {
    final LinkedHashMap<Integer, String> caretOffsetToExpectedResult =
      DartTestUtils.extractPositionMarkers(getProject(), myFixture.getEditor().getDocument());

    for (Map.Entry<Integer, String> entry : caretOffsetToExpectedResult.entrySet()) {
      final Integer caretOffset = entry.getKey();
      final String expectedResult = entry.getValue();

      final int line = myFixture.getEditor().getDocument().getLineNumber(caretOffset);
      final int column = caretOffset - myFixture.getEditor().getDocument().getLineStartOffset(line);
      final String fileNameAndPosition = myFixture.getFile().getName() + ":" + (line + 1) + ":" + (column + 1);

      final PsiReference reference = myFixture.getFile().findReferenceAt(caretOffset);
      assertNotNull("No reference in " + fileNameAndPosition, reference);

      final PsiElement resolve = reference.resolve();
      final String actualElementPosition = getPresentableElementPosition(resolve);
      assertEquals("Incorrect resolve for element in " + fileNameAndPosition, expectedResult, actualElementPosition);
    }
  }

  @NotNull
  private static String getPresentableElementPosition(final @Nullable PsiElement element) {
    if (element == null) return "";

    final StringBuilder buf = new StringBuilder(element.getText());
    DartComponentName componentName = PsiTreeUtil.getParentOfType(element, DartComponentName.class);
    while (componentName != null) {
      buf.insert(0, componentName.getName() + " -> ");
      componentName = PsiTreeUtil.getParentOfType(componentName, DartComponentName.class);
    }
    buf.insert(0, element.getContainingFile().getName() + " -> ");

    return buf.toString();
  }

  public void testNoRecursiveImports() throws Exception {
    myFixture.configureByText("file2.dart", "inFile2(){}");
    myFixture.configureByText("file1.dart", "import 'file2.dart'\n inFile1(){}");
    myFixture.configureByText("file.dart", "library fileLib;" +
                                           "import 'file1.dart';\n" +
                                           "part 'filePart1.dart';\n" +
                                           "part 'filePart2.dart';\n" +
                                           "inFile(){}");
    myFixture.configureByText("filePart1.dart", "part of fileLib;\n" +
                                                "inFilePart1(){}");
    myFixture.configureByText("filePart2.dart", "part of fileLib;\n" +
                                                "inFilePart2(){\n" +
                                                "  <caret expected='filePart1.dart -> inFilePart1'>inFilePart1()\n" +
                                                "  <caret expected='filePart2.dart -> inFilePart2'>inFilePart2()\n" +
                                                "  <caret expected='file.dart -> inFile'>inFile()\n" +
                                                "  <caret expected='file1.dart -> inFile1'>inFile1()\n" +
                                                "  <caret expected=''>inFile2()\n" +
                                                "}");
    doTest();
  }
}
