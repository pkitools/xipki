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

package org.xipki.ca.certprofile.xml;

import org.bouncycastle.asn1.x509.qualified.Iso4217CurrencyCode;
import org.xipki.ca.certprofile.xml.jaxb.Range2Type;
import org.xipki.common.util.ParamUtil;

/**
 * TODO.
 * @author Lijun Liao
 * @since 2.0.0
 */

class MonetaryValueOption {

  private final Iso4217CurrencyCode currency;

  private final String currencyString;

  private final Range2Type amountRange;

  private final Range2Type exponentRange;

  public MonetaryValueOption(Iso4217CurrencyCode currency, Range2Type amountRange,
      Range2Type exponentRange) {
    this.currency = ParamUtil.requireNonNull("currency", currency);
    this.amountRange = ParamUtil.requireNonNull("amountRange", amountRange);
    this.exponentRange = ParamUtil.requireNonNull("exponentRange", exponentRange);

    this.currencyString = currency.isAlphabetic() ? currency.getAlphabetic().toUpperCase()
        : Integer.toString(currency.getNumeric());
  }

  public Iso4217CurrencyCode getCurrency() {
    return currency;
  }

  public Range2Type getAmountRange() {
    return amountRange;
  }

  public Range2Type getExponentRange() {
    return exponentRange;
  }

  public String getCurrencyString() {
    return currencyString;
  }

}
