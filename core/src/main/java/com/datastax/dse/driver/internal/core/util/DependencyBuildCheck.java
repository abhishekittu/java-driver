/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.dse.driver.internal.core.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.Properties;

public class DependencyBuildCheck {

  /**
   * Utility method to assert that a given resource exists relative to the project's base directory.
   * Currently, this is only being used in tests to guarantee the existence of build artifacts. This
   * method assumes the resource you are looking for is available at a location relative to the base
   * directory of the project from which it is invoked. For example: if you are looking for
   * "deps.txt" in the core project, which should be generated during the Maven generate-resources
   * phase of the build, you would call this method like this:
   *
   * <pre>{@code
   * DependencyBuildCheck.assertFileInResourceExists(
   *     "target", "classes", "com", "datastax", "dse", "driver", "internal", "deps.txt");
   * }</pre>
   *
   * @param relativePaths array of ordered path Strings, including the resource file's base name.
   * @throws IOException if something goes wrong trying to locate or parse the "project.properties"
   *     resource file, or if parsing.
   */
  public static void assertFileInResourceExists(String... relativePaths) throws IOException {
    Properties projectProperties = new Properties();
    try (InputStream is = DependencyBuildCheck.class.getResourceAsStream("/project.properties")) {
      projectProperties.load(is);
      assert (Paths.get(projectProperties.getProperty("project.basedir"), relativePaths)
          .toFile()
          .exists());
    }
  }
}
