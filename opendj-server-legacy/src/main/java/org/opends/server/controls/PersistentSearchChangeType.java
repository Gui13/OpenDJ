/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions Copyright [year] [name of copyright owner]".
 *
 * Copyright 2006-2008 Sun Microsystems, Inc.
 * Portions Copyright 2013-2015 ForgeRock AS.
 */
package org.opends.server.controls;

import static org.opends.messages.ProtocolMessages.*;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.forgerock.i18n.LocalizableMessage;
import org.opends.server.protocols.ldap.LDAPResultCode;
import org.opends.server.types.LDAPException;

/**
 * This enumeration defines the set of possible change types that may be used in
 * conjunction with the persistent search control, as defined in
 * draft-ietf-ldapext-psearch.
 * <p>
 * It is a different type from {@link ChangeOperationType} to enforce type
 * safety, despite mirroring it completely.
 */
public enum PersistentSearchChangeType
{
  /** The change type that will be used for add operations. */
  ADD(1),

  /** The change type that will be used for delete operations. */
  DELETE(2),

  /** The change type that will be used for modify operations. */
  MODIFY(4),

  /** The change type that will be used for modify DN operations. */
  MODIFY_DN(8);



  /** The integer value associated with this change type. */
  private int intValue;

  /**
   * Creates a new instance of a persistent search change type with the provided
   * integer value.
   *
   * @param  intValue  The integer value associated with this change type.
   */
  private PersistentSearchChangeType(int intValue)
  {
    this.intValue = intValue;
  }



  /**
   * Retrieves the integer value associated with this change type.
   *
   * @return  The integer value associated with this change type.
   */
  public int intValue()
  {
    return intValue;
  }



  /**
   * Retrieves a string representation of this persistent search change type.
   *
   * @return  A string representation of this persistent search change type, or
   *          "unknown" if it has an unknown type.
   */
  @Override
  public String toString()
  {
    switch (intValue)
    {
      case 1:
        return "add";
      case 2:
        return "delete";
      case 4:
        return "modify";
      case 8:
        return "modDN";
      default:
        return "unknown";
    }
  }



  /**
   * Retrieves the change type associated with the provided integer value.
   *
   * @param  intValue  The integer value to decode as a change type.
   *
   * @return  The change type corresponding to the provided integer value.
   *
   * @throws  LDAPException  If the provided integer value is not associated
   *                         with a valid change type.
   */
  public static PersistentSearchChangeType valueOf(int intValue)
         throws LDAPException
  {
    switch (intValue)
    {
      case 1:
        return ADD;
      case 2:
        return DELETE;
      case 4:
        return MODIFY;
      case 8:
        return MODIFY_DN;
      default:
        LocalizableMessage message = ERR_PSEARCH_CHANGETYPES_INVALID_TYPE.get(intValue);
        throw new LDAPException(LDAPResultCode.CONSTRAINT_VIOLATION, message);
    }
  }



  /**
   * Decodes the provided int value into a set of change types as per the
   * specification in draft-ietf-ldapext-psearch.
   *
   * @param  intValue  The integer value representing the encoded change types.
   *
   * @return  The set of change types decoded from the provided integer value.
   *
   * @throws  LDAPException  If the provided integer value does not represent a
   *                         valid encoded set of change types.
   */
  public static Set<PersistentSearchChangeType> intToTypes(int intValue)
         throws LDAPException
  {
    Set<PersistentSearchChangeType> changeTypes = new HashSet<>(4);

    switch (intValue)
    {
      case 0:
        // No change types are included.  This won't be allowed because it
        // doesn't make any sense.
        LocalizableMessage message = ERR_PSEARCH_CHANGETYPES_NO_TYPES.get();
        throw new LDAPException(LDAPResultCode.CONSTRAINT_VIOLATION, message);
      case 1:
        changeTypes.add(ADD);
        break;
      case 2:
        changeTypes.add(DELETE);
        break;
      case 3:
        changeTypes.add(ADD);
        changeTypes.add(DELETE);
        break;
      case 4:
        changeTypes.add(MODIFY);
        break;
      case 5:
        changeTypes.add(ADD);
        changeTypes.add(MODIFY);
        break;
      case 6:
        changeTypes.add(DELETE);
        changeTypes.add(MODIFY);
        break;
      case 7:
        changeTypes.add(ADD);
        changeTypes.add(DELETE);
        changeTypes.add(MODIFY);
        break;
      case 8:
        changeTypes.add(MODIFY_DN);
        break;
      case 9:
        changeTypes.add(ADD);
        changeTypes.add(MODIFY_DN);
        break;
      case 10:
        changeTypes.add(DELETE);
        changeTypes.add(MODIFY_DN);
        break;
      case 11:
        changeTypes.add(ADD);
        changeTypes.add(DELETE);
        changeTypes.add(MODIFY_DN);
        break;
      case 12:
        changeTypes.add(MODIFY);
        changeTypes.add(MODIFY_DN);
        break;
      case 13:
        changeTypes.add(ADD);
        changeTypes.add(MODIFY);
        changeTypes.add(MODIFY_DN);
        break;
      case 14:
        changeTypes.add(DELETE);
        changeTypes.add(MODIFY);
        changeTypes.add(MODIFY_DN);
        break;
      case 15:
        changeTypes.add(ADD);
        changeTypes.add(DELETE);
        changeTypes.add(MODIFY);
        changeTypes.add(MODIFY_DN);
        break;
     default:
        message = ERR_PSEARCH_CHANGETYPES_INVALID_TYPES.get(intValue);
        throw new LDAPException(LDAPResultCode.CONSTRAINT_VIOLATION, message);
    }

    return changeTypes;
  }



  /**
   * Retrieves the integer representation of the provided set of change types.
   *
   * @param  changeTypes  The set of change types to be encoded.
   *
   * @return  The integer representation of the provided set of change types.
   */
  public static int changeTypesToInt(Set<PersistentSearchChangeType>
                                          changeTypes)
  {
    int intValue = 0;

    if (changeTypes.contains(ADD))
    {
      intValue |= 1;
    }

    if (changeTypes.contains(DELETE))
    {
      intValue |= 2;
    }

    if (changeTypes.contains(MODIFY))
    {
      intValue |= 4;
    }

    if (changeTypes.contains(MODIFY_DN))
    {
      intValue |= 8;
    }

    return intValue;
  }



  /**
   * Retrieves a string representation of the provided set of change types.
   *
   * @param  changeTypes  The set of change types to encode.
   *
   * @return  A string representation of the provided set of change types.
   */
  public static String changeTypesToString(Set<PersistentSearchChangeType>
                                                changeTypes)
  {
    StringBuilder buffer = new StringBuilder();
    changeTypesToString(changeTypes, buffer);
    return buffer.toString();
  }



  /**
   * Appends a string representation of the specified set of change types to the
   * provided buffer.
   *
   * @param  changeTypes  The set of change types to encode.
   * @param  buffer       The buffer to which the information should be written.
   */
  public static void changeTypesToString(Set<PersistentSearchChangeType>
                                              changeTypes,
                                         StringBuilder buffer)
  {
    Iterator<PersistentSearchChangeType> iterator = changeTypes.iterator();
    if (iterator.hasNext())
    {
      buffer.append(iterator.next());

      while (iterator.hasNext())
      {
        buffer.append("|");
        buffer.append(iterator.next());
      }
    }
  }
}

