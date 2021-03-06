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

package org.xipki.ca.qa.internal;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import org.xipki.ca.certprofile.xml.jaxb.GeneralSubtreeBaseType;
import org.xipki.ca.certprofile.xml.jaxb.NameConstraints;
import org.xipki.common.util.CollectionUtil;
import org.xipki.common.util.ParamUtil;

/**
 * TODO.
 * @author Lijun Liao
 * @since 2.0.0
 */

public class QaNameConstraints extends QaExtension {

  private final List<QaGeneralSubtree> permittedSubtrees;

  private final List<QaGeneralSubtree> excludedSubtrees;

  public QaNameConstraints(NameConstraints jaxb) {
    ParamUtil.requireNonNull("jaxb", jaxb);
    if (jaxb.getPermittedSubtrees() != null
        && CollectionUtil.isNonEmpty(jaxb.getPermittedSubtrees().getBase())) {
      List<QaGeneralSubtree> list = new LinkedList<>();
      List<GeneralSubtreeBaseType> bases = jaxb.getPermittedSubtrees().getBase();
      for (GeneralSubtreeBaseType base : bases) {
        list.add(new QaGeneralSubtree(base));
      }
      this.permittedSubtrees = Collections.unmodifiableList(list);
    } else {
      permittedSubtrees = null;
    }

    if (jaxb.getExcludedSubtrees() != null
        && CollectionUtil.isNonEmpty(jaxb.getExcludedSubtrees().getBase())) {
      List<QaGeneralSubtree> list = new LinkedList<>();
      List<GeneralSubtreeBaseType> bases = jaxb.getExcludedSubtrees().getBase();
      for (GeneralSubtreeBaseType base : bases) {
        list.add(new QaGeneralSubtree(base));
      }
      this.excludedSubtrees = Collections.unmodifiableList(list);
    } else {
      excludedSubtrees = null;
    }

    if (permittedSubtrees == null && excludedSubtrees == null) {
      throw new IllegalArgumentException(
          "at least one of permittedSubtrees and excludedSubtrees should be non-null");
    }
  }

  public List<QaGeneralSubtree> getPermittedSubtrees() {
    return permittedSubtrees;
  }

  public List<QaGeneralSubtree> getExcludedSubtrees() {
    return excludedSubtrees;
  }

}
