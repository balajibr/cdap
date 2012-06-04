/**
 * Copyright (C) 2012 Continuuity, Inc.
 */
package com.continuuity.data.operation.executor.omid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.hbase.util.Bytes;

import com.continuuity.common.utils.ImmutablePair;
import com.continuuity.data.SyncReadTimeoutException;
import com.continuuity.data.operation.CompareAndSwap;
import com.continuuity.data.operation.Delete;
import com.continuuity.data.operation.Increment;
import com.continuuity.data.operation.OperationGenerator;
import com.continuuity.data.operation.OrderedRead;
import com.continuuity.data.operation.OrderedWrite;
import com.continuuity.data.operation.Read;
import com.continuuity.data.operation.ReadCounter;
import com.continuuity.data.operation.ReadModifyWrite;
import com.continuuity.data.operation.Write;
import com.continuuity.data.operation.WriteOperationComparator;
import com.continuuity.data.operation.executor.BatchOperationResult;
import com.continuuity.data.operation.executor.TransactionalOperationExecutor;
import com.continuuity.data.operation.executor.omid.QueueInvalidate.QueueFinalize;
import com.continuuity.data.operation.executor.omid.QueueInvalidate.QueueUnack;
import com.continuuity.data.operation.executor.omid.QueueInvalidate.QueueUnenqueue;
import com.continuuity.data.operation.executor.omid.memory.MemoryRowSet;
import com.continuuity.data.operation.ttqueue.DequeueResult;
import com.continuuity.data.operation.ttqueue.EnqueueResult;
import com.continuuity.data.operation.ttqueue.QueueAck;
import com.continuuity.data.operation.ttqueue.DequeueResult.DequeueStatus;
import com.continuuity.data.operation.ttqueue.QueueAdmin.GetGroupID;
import com.continuuity.data.operation.ttqueue.QueueDequeue;
import com.continuuity.data.operation.ttqueue.QueueEnqueue;
import com.continuuity.data.operation.ttqueue.TTQueueTable;
import com.continuuity.data.operation.type.WriteOperation;
import com.continuuity.data.table.OVCTableHandle;
import com.continuuity.data.table.OrderedVersionedColumnarTable;
import com.continuuity.data.table.ReadPointer;

/**
 *
 */
public class OmidTransactionalOperationExecutor implements
TransactionalOperationExecutor {

  final TransactionOracle oracle;

  final OVCTableHandle tableHandle;

  final OrderedVersionedColumnarTable randomTable;

  final OrderedVersionedColumnarTable orderedTable;

  final TTQueueTable queueTable;

  static final byte [] COLUMN = Bytes.toBytes("c");

  public OmidTransactionalOperationExecutor(TransactionOracle oracle,
      OVCTableHandle tableHandle) {
    this.oracle = oracle;
    this.tableHandle = tableHandle;
    this.randomTable = tableHandle.getTable(Bytes.toBytes("random"));
    this.orderedTable = tableHandle.getTable(Bytes.toBytes("ordered"));
    this.queueTable = tableHandle.getQueueTable(Bytes.toBytes("queues"));
  }

  @Override
  public byte[] execute(Read read) throws SyncReadTimeoutException {
    return read(read, this.oracle.getReadPointer());
  }

  byte [] read(Read read, ReadPointer pointer) {
    return this.randomTable.get(read.getKey(), COLUMN, pointer);
  }

  @Override
  public long execute(ReadCounter readCounter) throws SyncReadTimeoutException {
    byte [] value = this.randomTable.get(readCounter.getKey(), COLUMN,
        this.oracle.getReadPointer());
    if (value == null || value.length != 8) return 0;
    return Bytes.toLong(value);
  }

  @Override
  public BatchOperationResult execute(List<WriteOperation> writes)
      throws OmidTransactionException {
    return execute(writes, startTransaction());
  }

  BatchOperationResult execute(List<WriteOperation> writes,
      ImmutablePair<ReadPointer,Long> pointer)
          throws OmidTransactionException {

    // Re-order operations (create a copy for now)
    List<WriteOperation> orderedWrites = new ArrayList<WriteOperation>(writes);
    Collections.sort(orderedWrites, new WriteOperationComparator());

    // Execute operations
    RowSet rows = new MemoryRowSet();
    List<Delete> deletes = new ArrayList<Delete>(writes.size());
    List<QueueInvalidate> invalidates = new ArrayList<QueueInvalidate>();
    for (WriteOperation write : orderedWrites) {
      WriteTransactionResult writeTxReturn = dispatchWrite(write, pointer);
      if (!writeTxReturn.success) {
        // Write operation failed
        abortTransaction(pointer, deletes, invalidates);
        return new BatchOperationResult(false,
            "A write operation failed, transaction aborted");
      } else {
        // Write was successful.  Store delete if we need to abort and continue
        deletes.addAll(writeTxReturn.deletes);
        if (writeTxReturn.invalidate != null) {
          // Queue operation
          invalidates.add(writeTxReturn.invalidate);
        } else {
          // Normal write operation
          rows.addRow(write.getKey());
        }
      }
    }

    // All operations completed successfully, commit transaction
    if (!commitTransaction(pointer, rows)) {
      // Commit failed, abort
      abortTransaction(pointer, deletes, invalidates);
      return new BatchOperationResult(false,
          "Commit of transaction failed, transaction aborted");
    }

    // If last operation was an ack, finalize it
    if (orderedWrites.get(orderedWrites.size() - 1) instanceof QueueAck) {
      QueueAck ack = (QueueAck)orderedWrites.get(orderedWrites.size() - 1);
      new QueueFinalize(ack.getKey(), ack.getEntryPointer(), ack.getConsumer())
          .execute(this.queueTable, pointer);
    }

    // Transaction was successfully committed
    return new BatchOperationResult(true);
  }

  private class WriteTransactionResult {
    final boolean success;
    final List<Delete> deletes;
    final QueueInvalidate invalidate;
    WriteTransactionResult(boolean success) {
      this(success, new ArrayList<Delete>());
    }
    WriteTransactionResult(boolean success, Delete delete) {
      this(success, Arrays.asList(new Delete [] { delete } ));
    }
    WriteTransactionResult(boolean success, List<Delete> deletes) {
      this(success, deletes, null);
    }
    public WriteTransactionResult(boolean success,
        QueueInvalidate invalidate) {
      this(success, new ArrayList<Delete>(), invalidate);
    }
    WriteTransactionResult(boolean success, List<Delete> deletes,
        QueueInvalidate invalidate) {
      this.success = success;
      this.deletes = deletes;
      this.invalidate = invalidate;
    }
  }
  /**
   * Actually perform the various write operations.
   * @param write
   * @param pointer
   * @return
   */
  private WriteTransactionResult dispatchWrite(
      WriteOperation write, ImmutablePair<ReadPointer,Long> pointer) {
    if (write instanceof Write) {
      return write((Write)write, pointer);
    } else if (write instanceof ReadModifyWrite) {
      return write((ReadModifyWrite)write, pointer);
    } else if (write instanceof Increment) {
      return write((Increment)write, pointer);
    } else if (write instanceof CompareAndSwap) {
      return write((CompareAndSwap)write, pointer);
    } else if (write instanceof QueueEnqueue) {
      return write((QueueEnqueue)write, pointer);
    } else if (write instanceof QueueAck) {
      return write((QueueAck)write, pointer);
    }
    return new WriteTransactionResult(false);
  }

  WriteTransactionResult write(Write write,
      ImmutablePair<ReadPointer,Long> pointer) {
    this.randomTable.put(write.getKey(), COLUMN, pointer.getSecond(),
        write.getValue());
    return new WriteTransactionResult(true, new Delete(write.getKey(), COLUMN));
  }

  WriteTransactionResult write(ReadModifyWrite write,
      ImmutablePair<ReadPointer,Long> pointer) {
    // read
    byte [] value = this.randomTable.get(write.getKey(), COLUMN,
        pointer.getFirst());
    // modify
    byte [] newValue = write.getModifier().modify(value);
    // write
    this.randomTable.put(write.getKey(), COLUMN, pointer.getSecond(), newValue);
    return new WriteTransactionResult(true, new Delete(write.getKey(), COLUMN));
  }

  WriteTransactionResult write(Increment increment,
      ImmutablePair<ReadPointer,Long> pointer) {
    long incremented = this.randomTable.increment(increment.getKey(), COLUMN,
        increment.getAmount(), pointer.getFirst(), pointer.getSecond());
    List<Delete> deletes = new ArrayList<Delete>(2);
    deletes.add(new Delete(increment.getKey(), COLUMN));
    OperationGenerator<Long> generator =
        increment.getPostIncrementOperationGenerator();
    if (generator != null) {
      WriteOperation writeOperation =
          generator.generateWriteOperation(incremented);
      if (writeOperation != null) {
        WriteTransactionResult result = dispatchWrite(writeOperation, pointer);
        deletes.addAll(result.deletes);
        return new WriteTransactionResult(result.success, deletes);
      }
    }
    return new WriteTransactionResult(true, deletes);
  }

  WriteTransactionResult write(CompareAndSwap write,
      ImmutablePair<ReadPointer,Long> pointer) {
    boolean casReturn = this.randomTable.compareAndSwap(write.getKey(), COLUMN,
        write.getExpectedValue(), write.getNewValue(), pointer.getFirst(),
        pointer.getSecond());
    return new WriteTransactionResult(casReturn,
        new Delete(write.getKey(), COLUMN));
  }

  // TTQueues

  /**
   * Enqueue operations always succeed but can be rolled back.
   * 
   * They are rolled back with an invalidate.
   * 
   * @param write
   * @param pointer
   * @return
   */
  WriteTransactionResult write(QueueEnqueue enqueue,
      ImmutablePair<ReadPointer, Long> pointer) {
    EnqueueResult result = this.queueTable.enqueue(enqueue.getKey(),
        enqueue.getData(), pointer.getSecond());
    enqueue.setResult(result);
    return new WriteTransactionResult(true,
        new QueueUnenqueue(enqueue.getKey(), result.getEntryPointer()));
  }

  WriteTransactionResult write(QueueAck ack,
      ImmutablePair<ReadPointer, Long> pointer) {
    boolean result = this.queueTable.ack(ack.getKey(), ack.getEntryPointer(),
        ack.getConsumer());
    if (!result) {
      // Ack failed, roll back transaction
      return new WriteTransactionResult(false);
    }
    return new WriteTransactionResult(true,
        new QueueUnack(ack.getKey(), ack.getEntryPointer(), ack.getConsumer()));
  }

  static int MAX_DEQUEUE_RETRIES = 10;
  static long DEQUEUE_RETRY_SLEEP = 1;
  
  @Override
  public DequeueResult execute(QueueDequeue dequeue)
      throws SyncReadTimeoutException {
    int retries = 0;
    while (retries < MAX_DEQUEUE_RETRIES) {
      DequeueResult result = this.queueTable.dequeue(dequeue.getKey(),
          dequeue.getConsumer(), dequeue.getConfig(),
          this.oracle.getReadPointer());
      if (result.shouldRetry()) {
        retries++;
        try {
          if (DEQUEUE_RETRY_SLEEP > 0) Thread.sleep(DEQUEUE_RETRY_SLEEP);
        } catch (InterruptedException e) {
          e.printStackTrace();
          // continue in loop
        }
        continue;
      }
      dequeue.setResult(result);
      return result;
    }
    return new DequeueResult(DequeueStatus.FAILURE, "Maximum retries");
  }

  ImmutablePair<ReadPointer, Long> startTransaction() {
    return this.oracle.getNewPointer();
  }

  boolean commitTransaction(ImmutablePair<ReadPointer, Long> pointer,
      RowSet rows) throws OmidTransactionException {
    return this.oracle.commit(pointer.getSecond(), rows);
  }

  private void abortTransaction(ImmutablePair<ReadPointer,Long> pointer,
      List<Delete> deletes, List<QueueInvalidate> invalidates)
          throws OmidTransactionException {
    // Perform queue invalidates
    for (QueueInvalidate invalidate : invalidates) {
      invalidate.execute(this.queueTable, pointer);
    }
    // Perform deletes
    for (Delete delete : deletes) {
      assert(delete != null);
      this.randomTable.delete(delete.getKey(), delete.getColumn(),
          pointer.getSecond());
    }
    // Notify oracle
    this.oracle.aborted(pointer.getSecond());
  }

  // Queue operations also not supported right now

  @Override
  public Map<byte[], byte[]> execute(OrderedRead orderedRead)
      throws SyncReadTimeoutException {
    unsupported("Ordered operations not currently supported");
    return null;
  }

  // Single Write Operations (UNSUPPORTED IN TRANSACTIONAL!)

  private void unsupported() {
    unsupported(
        "Single write operations are not supported by transactional executors");
  }

  private void unsupported(String msg) {
    throw new RuntimeException(msg);
  }

  @Override
  public boolean execute(Write write) {
    unsupported();
    return false;
  }

  @Override
  public boolean execute(Delete delete) {
    unsupported();
    return false;
  }
  @Override
  public boolean execute(OrderedWrite write) {
    unsupported();
    return false;
  }

  @Override
  public boolean execute(ReadModifyWrite rmw) {
    unsupported();
    return false;
  }

  @Override
  public boolean execute(Increment inc) {
    unsupported();
    return false;
  }

  @Override
  public boolean execute(CompareAndSwap cas) {
    unsupported();
    return false;
  }

  @Override
  public long execute(GetGroupID getGroupId) throws SyncReadTimeoutException {
    unsupported();
    return 0L;
  }

  @Override
  public boolean execute(QueueAck ack) {
    unsupported();
    return false;
  }

  @Override
  public boolean execute(QueueEnqueue enqueue) {
    unsupported();
    return false;
  }
}
