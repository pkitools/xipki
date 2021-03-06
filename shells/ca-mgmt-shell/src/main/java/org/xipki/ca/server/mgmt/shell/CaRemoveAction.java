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

package org.xipki.ca.server.mgmt.shell;

import org.apache.karaf.shell.api.action.Argument;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Completion;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.xipki.ca.server.mgmt.api.CaMgmtException;
import org.xipki.ca.server.mgmt.shell.completer.CaNameCompleter;
import org.xipki.console.karaf.CmdFailure;

/**
 * TODO.
 * @author Lijun Liao
 * @since 2.0.0
 */

@Command(scope = "ca", name = "ca-rm",
    description = "remove CA")
@Service
public class CaRemoveAction extends CaAction {

  @Argument(index = 0, name = "name", required = true,
      description = "CA name")
  @Completion(CaNameCompleter.class)
  private String name;

  @Override
  protected Object execute0() throws Exception {
    String msg = "CA " + name;
    try {
      caManager.removeCa(name);
      println("removed " + msg);
      return null;
    } catch (CaMgmtException ex) {
      throw new CmdFailure("could not remove " + msg + ", error: " + ex.getMessage(), ex);
    }
  }

}
