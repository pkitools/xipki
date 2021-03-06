/*
 *
 * Copyright (c) 2013 - 2018 Lijun Liao
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

package org.xipki.ca.dbtool.shell;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.karaf.shell.api.action.Completion;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Reference;
import org.apache.karaf.shell.support.completers.FileCompleter;
import org.xipki.ca.dbtool.shell.completer.LogLevelCompleter;
import org.xipki.common.util.IoUtil;
import org.xipki.common.util.ParamUtil;
import org.xipki.common.util.StringUtil;
import org.xipki.console.karaf.XiAction;
import org.xipki.dbtool.LiquibaseDatabaseConf;
import org.xipki.dbtool.LiquibaseMain;
import org.xipki.password.PasswordResolver;
import org.xipki.password.PasswordResolverException;

/**
 * TODO.
 * @author Lijun Liao
 * @since 2.0.0
 */

public abstract class LiquibaseAction extends XiAction {

  private static final String DFLT_CACONF_FILE = "xipki/ca-config/ca.properties";

  private static final List<String> YES_NO = Arrays.asList("yes", "no");

  @Reference
  private PasswordResolver passwordResolver;

  @Option(name = "--force", aliases = "-f",
      description = "never prompt for confirmation")
  private Boolean force = Boolean.FALSE;

  @Option(name = "--log-level",
      description = "log level, valid values are debug, info, warning, severe, off")
  @Completion(LogLevelCompleter.class)
  private String logLevel = "warning";

  @Option(name = "--log-file",
      description = "log file")
  @Completion(FileCompleter.class)
  private String logFile;

  @Option(name = "--ca-conf",
      description = "CA configuration file")
  @Completion(FileCompleter.class)
  private String caconfFile = DFLT_CACONF_FILE;

  protected void resetAndInit(LiquibaseDatabaseConf dbConf, String schemaFile) throws Exception {
    ParamUtil.requireNonNull("dbConf", dbConf);
    ParamUtil.requireNonNull("schemaFile", schemaFile);

    printDatabaseInfo(dbConf, schemaFile);
    if (!force) {
      if (!confirm("reset and initialize")) {
        println("cancelled");
        return;
      }
    }

    LiquibaseMain liquibase = new LiquibaseMain(dbConf, schemaFile);
    try {
      liquibase.init(logLevel, logFile);
      liquibase.releaseLocks();
      liquibase.dropAll();
      liquibase.update();
    } finally {
      liquibase.shutdown();
    }

  }

  protected void update(LiquibaseDatabaseConf dbConf, String schemaFile) throws Exception {
    ParamUtil.requireNonNull("dbConf", dbConf);
    ParamUtil.requireNonNull("schemaFile", schemaFile);

    printDatabaseInfo(dbConf, schemaFile);
    if (!force) {
      if (!confirm("update")) {
        println("cancelled");
        return;
      }
    }

    LiquibaseMain liquibase = new LiquibaseMain(dbConf, schemaFile);
    try {
      liquibase.init(logLevel, logFile);
      liquibase.update();
    } finally {
      liquibase.shutdown();
    }

  }

  static void printDatabaseInfo(LiquibaseDatabaseConf dbParams, String schemaFile) {
    String msg = StringUtil.concat("\n--------------------------------------------",
        "\n     driver: ", dbParams.getDriver(),  "\n       user: ", dbParams.getUsername(),
        "\n        URL: ", dbParams.getUrl(),
        (dbParams.getSchema() != null ? "     schema: " + dbParams.getSchema() : ""),
        "\nschema file: ", schemaFile, "\n");

    System.out.println(msg);
  }

  protected Map<String, LiquibaseDatabaseConf> getDatabaseConfs()
      throws FileNotFoundException, IOException, PasswordResolverException {
    Map<String, LiquibaseDatabaseConf> ret = new HashMap<>();
    Properties props = getPropertiesFromFile(caconfFile);
    for (Object objKey : props.keySet()) {
      String key = (String) objKey;
      if (key.startsWith("datasource.")) {
        String datasourceFile = props.getProperty(key);
        String datasourceName = key.substring("datasource.".length());
        Properties dbConf = getDbConfPoperties(datasourceFile);
        LiquibaseDatabaseConf dbParams = LiquibaseDatabaseConf.getInstance(dbConf,
            passwordResolver);
        ret.put(datasourceName, dbParams);
      }
    }

    return ret;
  }

  private static Properties getDbConfPoperties(String dbconfFile)
      throws FileNotFoundException, IOException {
    Properties props = new Properties();
    props.load(new FileInputStream(IoUtil.expandFilepath(dbconfFile)));
    return props;
  }

  private static Properties getPropertiesFromFile(String propFile)
      throws FileNotFoundException, IOException {
    Properties props = new Properties();
    props.load(new FileInputStream(IoUtil.expandFilepath(propFile)));
    return props;
  }

  private boolean confirm(String command) throws IOException {
    String text = read("\nDo you wish to " + command + " the database", YES_NO);
    return "yes".equalsIgnoreCase(text);
  }

  private String read(String prompt, List<String> validValues) throws IOException {
    String tmpPrompt = prompt;
    List<String> tmpValidValues = validValues;
    if (tmpValidValues == null) {
      tmpValidValues = Collections.emptyList();
    }

    if (tmpPrompt == null) {
      tmpPrompt = "Please enter";
    }

    if (isNotEmpty(tmpValidValues)) {
      StringBuilder promptBuilder = new StringBuilder(tmpPrompt);
      promptBuilder.append(" [");

      for (String validValue : tmpValidValues) {
        promptBuilder.append(validValue).append("/");
      }
      promptBuilder.deleteCharAt(promptBuilder.length() - 1);
      promptBuilder.append("] ?");

      tmpPrompt = promptBuilder.toString();
    }

    while (true) {
      String answer = readPrompt(tmpPrompt);
      if (isEmpty(tmpValidValues) || tmpValidValues.contains(answer)) {
        return answer;
      } else {
        StringBuilder retryPromptBuilder = new StringBuilder("Please answer with ");
        for (String validValue : tmpValidValues) {
          retryPromptBuilder.append(validValue).append("/");
        }
        retryPromptBuilder.deleteCharAt(retryPromptBuilder.length() - 1);
        tmpPrompt = retryPromptBuilder.toString();
      }
    }
  } // method read

}
