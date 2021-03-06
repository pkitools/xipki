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

package org.xipki.p11proxy.msg;

import java.io.IOException;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1Object;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERUTF8String;
import org.xipki.common.util.ParamUtil;
import org.xipki.security.exception.BadAsn1ObjectException;
import org.xipki.security.pkcs11.P11NewKeyControl;
import org.xipki.security.pkcs11.P11SlotIdentifier;

/**
 * TODO.
 * <pre>
 * GenSecretKeyParams ::= SEQUENCE {
 *     slotId               P11SlotIdentifier,
 *     label                UTF8STRING,
 *     control              NewKeyControl,
 *     keyType              INTEGER,
 *     keysize              INTEGER }
 * </pre>
 *
 * @author Lijun Liao
 * @since 2.0.0
 */

public class Asn1GenSecretKeyParams extends ASN1Object {

  private final P11SlotIdentifier slotId;

  private final String label;

  private final P11NewKeyControl control;

  private final long keyType;

  private final int keysize;

  public Asn1GenSecretKeyParams(P11SlotIdentifier slotId, String label,
      P11NewKeyControl control, long keyType, int keysize) {
    this.slotId = ParamUtil.requireNonNull("slotId", slotId);
    this.label = ParamUtil.requireNonBlank("label", label);
    this.control = ParamUtil.requireNonNull("control", control);
    this.keyType = keyType;
    this.keysize = ParamUtil.requireMin("keysize", keysize, 1);
  }

  private Asn1GenSecretKeyParams(ASN1Sequence seq) throws BadAsn1ObjectException {
    Asn1Util.requireRange(seq, 5, 5);
    int idx = 0;
    slotId = Asn1P11SlotIdentifier.getInstance(seq.getObjectAt(idx++)).getSlotId();
    label = Asn1Util.getUtf8String(seq.getObjectAt(idx++));
    control = Asn1NewKeyControl.getInstance(seq.getObjectAt(idx++)).getControl();
    keyType = Asn1Util.getInteger(seq.getObjectAt(idx++)).longValue();
    keysize = Asn1Util.getInteger(seq.getObjectAt(idx++)).intValue();
    ParamUtil.requireMin("keysize", keysize, 1);
  }

  public static Asn1GenSecretKeyParams getInstance(Object obj) throws BadAsn1ObjectException {
    if (obj == null || obj instanceof Asn1GenSecretKeyParams) {
      return (Asn1GenSecretKeyParams) obj;
    }

    try {
      if (obj instanceof ASN1Sequence) {
        return new Asn1GenSecretKeyParams((ASN1Sequence) obj);
      } else if (obj instanceof byte[]) {
        return getInstance(ASN1Primitive.fromByteArray((byte[]) obj));
      } else {
        throw new BadAsn1ObjectException("unknown object: " + obj.getClass().getName());
      }
    } catch (IOException | IllegalArgumentException ex) {
      throw new BadAsn1ObjectException("unable to parse encoded object: " + ex.getMessage(), ex);
    }
  }

  @Override
  public ASN1Primitive toASN1Primitive() {
    ASN1EncodableVector vector = new ASN1EncodableVector();
    vector.add(new Asn1P11SlotIdentifier(slotId));
    vector.add(new DERUTF8String(label));
    vector.add(new Asn1NewKeyControl(control));
    vector.add(new ASN1Integer(keyType));
    vector.add(new ASN1Integer(keysize));
    return new DERSequence(vector);
  }

  public P11SlotIdentifier getSlotId() {
    return slotId;
  }

  public String getLabel() {
    return label;
  }

  public P11NewKeyControl getControl() {
    return control;
  }

  public long getKeyType() {
    return keyType;
  }

  public int getKeysize() {
    return keysize;
  }

}
