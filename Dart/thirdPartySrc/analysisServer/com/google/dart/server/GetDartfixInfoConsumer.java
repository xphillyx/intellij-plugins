/*
 * Copyright (c) 2019, the Dart project authors.
 *
 * Licensed under the Eclipse Public License v1.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.dart.server;


import org.dartlang.analysis.server.protocol.DartFix;
import org.dartlang.analysis.server.protocol.RequestError;

import java.util.List;

/**
 * The interface {@code GetDartfixInfoConsumer} defines the behavior of objects that consume
 * a get dartfix info request.
 *
 * @coverage dart.server
 */
public interface GetDartfixInfoConsumer extends Consumer {
  /**
   * @param fixes A list of fixes that can be specified in an edit.dartfix request.
   */
  public void computedDartfixInfo(List<DartFix> fixes);

  /**
   * If a transitive closure cannot be passed back, some {@link RequestError} is passed back
   * instead.
   *
   * @param requestError the reason why a result was not passed back
   */
  public void onError(RequestError requestError);
}
