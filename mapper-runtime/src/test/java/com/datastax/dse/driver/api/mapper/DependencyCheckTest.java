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
package com.datastax.dse.driver.api.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.Properties;
import org.junit.Test;

public class DependencyCheckTest {

  @Test
  public void should_generate_deps_txt() throws IOException {
    Properties projectProperties = new Properties();
    InputStream is = this.getClass().getResourceAsStream("/project.properties");
    projectProperties.load(is);
    assertThat(
            Paths.get(
                    projectProperties.getProperty("project.basedir"),
                    "target",
                    "classes",
                    "com",
                    "datastax",
                    "dse",
                    "driver",
                    "internal",
                    "mapper",
                    "deps.txt")
                .toFile())
        .exists();
  }
}
