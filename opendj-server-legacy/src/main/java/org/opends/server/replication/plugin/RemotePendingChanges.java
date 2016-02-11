/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License, Version 1.0 only
 * (the "License").  You may not use this file except in compliance
 * with the License.
 *
 * You can obtain a copy of the license at legal-notices/CDDLv1_0.txt
 * or http://forgerock.org/license/CDDLv1.0.html.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at legal-notices/CDDLv1_0.txt.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information:
 *      Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 *
 *
 *      Copyright 2007-2009 Sun Microsystems, Inc.
 *      Portions Copyright 2013-2016 ForgeRock AS.
 */
package org.opends.server.replication.plugin;

import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import net.jcip.annotations.GuardedBy;
import org.opends.server.core.AddOperation;
import org.opends.server.core.DeleteOperation;
import org.opends.server.core.ModifyDNOperationBasis;
import org.opends.server.core.ModifyOperation;
import org.opends.server.replication.common.CSN;
import org.opends.server.replication.common.ServerState;
import org.opends.server.replication.protocol.*;
import org.opends.server.types.DN;
import org.opends.server.types.Operation;

/**
 * This class is used to store the list of remote changes received
 * from a replication server and that are either currently being replayed
 * or that are waiting for being replayed.
 *
 * It is used to know when the ServerState must be updated and to compute
 * the dependencies between operations.
 *
 * One of this object is instantiated for each ReplicationDomain.
 */
final class RemotePendingChanges
{
  /** A map used to store the pending changes. */
  @GuardedBy("pendingChangesLock")
  private final SortedMap<CSN, PendingChange> pendingChanges = new TreeMap<>();

  /**
   * A sorted set containing the list of PendingChanges that have
   * not been replayed correctly because they are dependent on
   * another change to be completed.
   */
  @GuardedBy("dependentChangesLock")
  private final SortedSet<PendingChange> dependentChanges = new TreeSet<>();
  /**
   * {@code activeAndDependentChanges} also contains changes discovered to be dependent
   * on currently in progress changes.
   */
  private final ConcurrentSkipListSet<PendingChange> activeAndDependentChanges = new ConcurrentSkipListSet<>();

  private ReentrantReadWriteLock pendingChangesLock = new ReentrantReadWriteLock(true);
  private ReentrantReadWriteLock.ReadLock pendingChangesReadLock = pendingChangesLock.readLock();
  private ReentrantReadWriteLock.WriteLock pendingChangesWriteLock = pendingChangesLock.writeLock();
  private ReentrantLock dependentChangesLock = new ReentrantLock();

  /** The ServerState that will be updated when LDAPUpdateMsg are fully replayed. */
  private final ServerState state;

  /**
   * Creates a new RemotePendingChanges using the provided ServerState.
   *
   * @param state   The ServerState that will be updated when LDAPUpdateMsg
   *                have been fully replayed.
   */
  public RemotePendingChanges(ServerState state)
  {
    this.state = state;
  }

  /**
   * Returns the number of changes waiting to be replayed.
   *
   * @return The number of changes waiting to be replayed
   */
  public int getQueueSize()
  {
    pendingChangesReadLock.lock();
    try
    {
      return pendingChanges.size();
    }
    finally
    {
      pendingChangesReadLock.unlock();
    }
  }

  /**
   * Returns the number of changes actively being replayed.
   *
   * @return the number of changes actively being replayed.
   */
  public int changesInProgressSize()
  {
    return activeAndDependentChanges.size();
  }

  /**
   * Returns the number of changes depending on other changes.
   *
   * @return the number of changes depending on other changes.
   */
  public int getDependentChangesSize()
  {
    dependentChangesLock.lock();
    try
    {
      return dependentChanges.size();
    }
    finally
    {
      dependentChangesLock.unlock();
    }
  }

  /**
   * Add a new LDAPUpdateMsg that was received from the replication server
   * to the pendingList.
   *
   * @param update The LDAPUpdateMsg that was received from the replication
   *               server and that will be added to the pending list.
   * @return {@code false} if the update was already registered in the pending
   *         changes.
   */
  public boolean putRemoteUpdate(LDAPUpdateMsg update)
  {
    pendingChangesWriteLock.lock();
    try
    {
      CSN csn = update.getCSN();
      return pendingChanges.put(csn, new PendingChange(csn, null, update)) == null;
    }
    finally
    {
      pendingChangesWriteLock.unlock();
    }
  }

  /**
   * Mark an update message as committed.
   *
   * @param csn
   *          The CSN of the update message that must be set as committed.
   */
  public void commit(CSN csn)
  {
    pendingChangesWriteLock.lock();
    try
    {
      PendingChange curChange = pendingChanges.get(csn);
      if (curChange == null)
      {
        throw new NoSuchElementException();
      }
      curChange.setCommitted(true);
      activeAndDependentChanges.remove(curChange);

      Iterator<Map.Entry<CSN, PendingChange>> it = pendingChanges.entrySet().iterator();
      while (it.hasNext())
      {
        Map.Entry<CSN, PendingChange> change = it.next();
        PendingChange pendingChange = change.getValue();
        if (!pendingChange.isCommitted())
        {
          break;
        }
        if (pendingChange.getMsg().contributesToDomainState())
        {
          state.update(change.getKey());
        }
        it.remove();
      }
    }
    finally
    {
      pendingChangesWriteLock.unlock();
    }
  }

  public void markInProgress(LDAPUpdateMsg msg)
  {
    pendingChangesReadLock.lock();
    try
    {
      activeAndDependentChanges.add(pendingChanges.get(msg.getCSN()));
    }
    finally
    {
      pendingChangesReadLock.unlock();
    }
  }
  /**
   * Get the first update in the list that have some dependencies cleared.
   *
   * @return The LDAPUpdateMsg to be handled.
   */
  public LDAPUpdateMsg getNextUpdate()
  {
    pendingChangesReadLock.lock();
    dependentChangesLock.lock();
    try
    {
      if (!dependentChanges.isEmpty())
      {
        PendingChange firstDependentChange = dependentChanges.first();
        if (pendingChanges.firstKey().isNewerThanOrEqualTo(firstDependentChange.getCSN()))
        {
          dependentChanges.remove(firstDependentChange);
          return firstDependentChange.getLDAPUpdateMsg();
        }
      }
      return null;
    }
    finally
    {
      dependentChangesLock.unlock();
      pendingChangesReadLock.unlock();
    }
  }

  /**
   * Mark the first pendingChange as dependent on the second PendingChange.
   * @param dependentChange The PendingChange that depend on the second
   *                        PendingChange.
   */
  private void addDependency(PendingChange dependentChange)
  {
    dependentChangesLock.lock();
    try
    {
      dependentChanges.add(dependentChange);
    }
    finally
    {
      dependentChangesLock.unlock();
    }
  }

  private PendingChange getPendingChange(CSN csn)
  {
    pendingChangesReadLock.lock();
    try
    {
      return pendingChanges.get(csn);
    }
    finally
    {
      pendingChangesReadLock.unlock();
    }
  }

  /**
   * Check if the given AddOperation has some dependencies on any
   * currently running previous operation.
   * Update the dependency list in the associated PendingChange if
   * there are some dependencies.
   * AddOperation depends on
   *
   * - DeleteOperation done on the same DN
   * - ModifyDnOperation with the same target DN as the ADD DN
   * - ModifyDnOperation with new DN equals to the ADD DN parent
   * - AddOperation done on the parent DN of the ADD DN
   *
   * @param op The AddOperation to be checked.
   *
   * @return A boolean indicating if this operation has some dependencies.
   */
  public boolean checkDependencies(AddOperation op)
  {
    boolean hasDependencies = false;
    final DN targetDN = op.getEntryDN();
    final CSN csn = OperationContext.getCSN(op);
    final PendingChange change = getPendingChange(csn);

    if (change == null)
    {
      return false;
    }

    for (PendingChange pendingChange : activeAndDependentChanges)
    {
      if (pendingChange.getCSN().isNewerThanOrEqualTo(csn))
      {
        // From now on, the dependency should be for newer changes to be dependent on this one, so we can stop for now.
        break;
      }
      final LDAPUpdateMsg pendingMsg = pendingChange.getLDAPUpdateMsg();
      if (pendingMsg instanceof DeleteMsg)
      {
          /*
           * Check if the operation to be run is a deleteOperation on the same DN.
           */
        if (pendingMsg.getDN().equals(targetDN))
        {
          hasDependencies = true;
          addDependency(change);
        }
      }
      else if (pendingMsg instanceof AddMsg)
      {
          /*
           * Check if the operation to be run is an addOperation on a
           * parent of the current AddOperation.
           */
        if (pendingMsg.getDN().isSuperiorOrEqualTo(targetDN))
        {
          hasDependencies = true;
          addDependency(change);
        }
      }
      else if (pendingMsg instanceof ModifyDNMsg)
      {
          /*
           * Check if the operation to be run is ModifyDnOperation with
           * the same target DN as the ADD DN
           * or a ModifyDnOperation with new DN equals to the ADD DN parent
           */
        if (pendingMsg.getDN().equals(targetDN))
        {
          hasDependencies = true;
          addDependency(change);
        }
        else
        {
          final ModifyDNMsg pendingModDn = (ModifyDNMsg) pendingMsg;
          if (pendingModDn.newDNIsParent(targetDN))
          {
            hasDependencies = true;
            addDependency(change);
          }
        }
      }
    }
    return hasDependencies;
  }

  /**
   * Check if the given ModifyOperation has some dependencies on any
   * currently running previous operation.
   * Update the dependency list in the associated PendingChange if
   * there are some dependencies.
   *
   * ModifyOperation depends on
   * - AddOperation done on the same DN
   * - ModifyDNOperation having newDN the same as targetDN
   *
   * @param op The ModifyOperation to be checked.
   *
   * @return A boolean indicating if this operation has some dependencies.
   */
  public boolean checkDependencies(ModifyOperation op)
  {
    boolean hasDependencies = false;
    final CSN csn = OperationContext.getCSN(op);
    final PendingChange change = getPendingChange(csn);

    if (change == null)
    {
        return false;
    }

    final DN targetDN = change.getLDAPUpdateMsg().getDN();
    for (PendingChange pendingChange : activeAndDependentChanges)
    {
      if (pendingChange.getCSN().isNewerThanOrEqualTo(csn))
      {
        // From now on, the dependency should be for newer changes to be dependent on this one, so we can stop for now.
        break;
      }
      final LDAPUpdateMsg pendingMsg = pendingChange.getLDAPUpdateMsg();
      if (pendingMsg instanceof AddMsg)
      {
        // Check if the operation to be run is an addOperation on a same DN.
        if (pendingMsg.getDN().equals(targetDN))
        {
          hasDependencies = true;
          addDependency(change);
        }
      }
      else if (pendingMsg instanceof ModifyDNMsg)
      {
        if (((ModifyDNMsg) pendingMsg).newDNIsEqual(targetDN))
        {
          hasDependencies = true;
          addDependency(change);
        }
      }
    }
    return hasDependencies;
  }

  /**
   * Check if the given ModifyDNMsg has some dependencies on any
   * currently running previous operation.
   * Update the dependency list in the associated PendingChange if
   * there are some dependencies.
   *
   * Modify DN Operation depends on
   * - AddOperation done on the same DN as the target DN of the MODDN operation
   * - AddOperation done on the new parent of the MODDN  operation
   * - DeleteOperation done on the new DN of the MODDN operation
   * - ModifyDNOperation done from the new DN of the MODDN operation
   *
   * TODO: Consider cases where there is a rename A -> B then rename B -> C. Second change depends on first
   *
   * @param msg The ModifyDNMsg to be checked.
   *
   * @return A boolean indicating if this operation has some dependencies.
   */
  public boolean checkDependencies(ModifyDNMsg msg)
  {
    boolean hasDependencies = false;
    final CSN csn = msg.getCSN();
    final PendingChange change = getPendingChange(csn);

    if (change == null)
    {
      return false;
    }

    final DN targetDN = change.getLDAPUpdateMsg().getDN();
    for (PendingChange pendingChange : activeAndDependentChanges)
    {
      if (pendingChange.getCSN().isNewerThanOrEqualTo(csn))
      {
        // From now on, the dependency should be for newer changes to be dependent on this one, so we can stop for now.
        break;
      }
      final LDAPUpdateMsg pendingMsg = pendingChange.getLDAPUpdateMsg();
      if (pendingMsg instanceof DeleteMsg)
      {
        // Check if the target of the Delete is the same
        // as the new DN of this ModifyDN
        if (msg.newDNIsEqual(pendingMsg.getDN()))
        {
          hasDependencies = true;
          addDependency(change);
        }
      }
      else if (pendingMsg instanceof AddMsg)
      {
        // Check if the Add Operation was done on the new parent of
        // the MODDN  operation
        if (msg.newParentIsEqual(pendingMsg.getDN()))
        {
          hasDependencies = true;
          addDependency(change);
        }
        // Check if the AddOperation was done on the same DN as the
        // target DN of the MODDN operation
        if (pendingMsg.getDN().equals(targetDN))
        {
          hasDependencies = true;
          addDependency(change);
        }
      }
      else if (pendingMsg instanceof ModifyDNMsg)
      {
        // Check if the ModifyDNOperation was done from the new DN of
        // the MODDN operation
        if (msg.newDNIsEqual(pendingMsg.getDN()))
        {
          hasDependencies = true;
          addDependency(change);
        }
      }
    }
    return hasDependencies;
  }

  /**
   * Check if the given DeleteOperation has some dependencies on any
   * currently running previous operation.
   * Update the dependency list in the associated PendingChange if
   * there are some dependencies.
   *
   * DeleteOperation depends on
   * - DeleteOperation done on children DN
   * - ModifyDnOperation with target DN that are children of the DEL DN
   * - AddOperation done on the same DN
   *
   *
   * @param op The DeleteOperation to be checked.
   *
   * @return A boolean indicating if this operation has some dependencies.
   */
  public boolean checkDependencies(DeleteOperation op)
  {
    boolean hasDependencies = false;
    final DN targetDN = op.getEntryDN();
    final CSN csn = OperationContext.getCSN(op);
    final PendingChange change = getPendingChange(csn);

    if (change == null)
    {
      return false;
    }

    for (PendingChange pendingChange : activeAndDependentChanges)
    {
      if (pendingChange.getCSN().isNewerThanOrEqualTo(csn))
      {
        // From now on, the dependency should be for newer changes to be dependent on this one, so we can stop for now.
        break;
      }
      final LDAPUpdateMsg pendingMsg = pendingChange.getLDAPUpdateMsg();
      if (pendingMsg instanceof DeleteMsg)
      {
          /*
           * Check if the operation to be run is a deleteOperation on a
           * children of the current DeleteOperation.
           */
        if (pendingMsg.getDN().isSubordinateOrEqualTo(targetDN))
        {
          hasDependencies = true;
          addDependency(change);
        }
      }
      else if (pendingMsg instanceof AddMsg)
      {
          /*
           * Check if the operation to be run is an addOperation on a
           * parent of the current DeleteOperation.
           */
        if (pendingMsg.getDN().equals(targetDN))
        {
          hasDependencies = true;
          addDependency(change);
        }
      }
      else if (pendingMsg instanceof ModifyDNMsg)
      {
        final ModifyDNMsg pendingModDn = (ModifyDNMsg) pendingMsg;
          /*
           * Check if the operation to be run is an ModifyDNOperation
           * on a children of the current DeleteOperation
           */
        if (pendingMsg.getDN().isSubordinateOrEqualTo(targetDN) || pendingModDn.newDNIsParent(targetDN))
        {
          hasDependencies = true;
          addDependency(change);
        }
      }
    }
    return hasDependencies;
  }

  /**
   * Check the dependencies of a given Operation/UpdateMsg.
   *
   * @param op   The Operation for which dependencies must be checked.
   * @param msg  The LocalizableMessage for which dependencies must be checked.
   * @return     A boolean indicating if an operation cannot be replayed
   *             because of dependencies.
   */
  public boolean checkDependencies(Operation op, LDAPUpdateMsg msg)
  {
    if (op instanceof ModifyOperation)
    {
      ModifyOperation newOp = (ModifyOperation) op;
      return checkDependencies(newOp);

    } else if (op instanceof DeleteOperation)
    {
      DeleteOperation newOp = (DeleteOperation) op;
      return checkDependencies(newOp);
    }
    else if (op instanceof AddOperation)
    {
      AddOperation newOp = (AddOperation) op;
      return checkDependencies(newOp);
    }
    else if (op instanceof ModifyDNOperationBasis)
    {
      ModifyDNMsg newMsg = (ModifyDNMsg) msg;
      return checkDependencies(newMsg);
    }
    else
    {
      return true;  // unknown type of operation ?!
    }
  }
}
