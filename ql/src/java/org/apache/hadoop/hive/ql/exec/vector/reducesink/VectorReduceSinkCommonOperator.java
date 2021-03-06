/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.exec.vector.reducesink;

import java.io.IOException;
import java.util.Arrays;
import java.util.Properties;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.ql.CompilationOpContext;
import org.apache.hadoop.hive.ql.exec.Operator;
import org.apache.hadoop.hive.ql.exec.ReduceSinkOperator.Counter;
import org.apache.hadoop.hive.ql.exec.TerminalOperator;
import org.apache.hadoop.hive.ql.exec.Utilities;
import org.apache.hadoop.hive.ql.exec.vector.VectorSerializeRow;
import org.apache.hadoop.hive.ql.exec.vector.VectorizationContext;
import org.apache.hadoop.hive.ql.exec.vector.VectorizationContextRegion;
import org.apache.hadoop.hive.ql.exec.vector.VectorizedRowBatch;
import org.apache.hadoop.hive.ql.exec.vector.expressions.VectorExpression;
import org.apache.hadoop.hive.ql.exec.vector.keyseries.VectorKeySeriesSerialized;
import org.apache.hadoop.hive.ql.io.HiveKey;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.plan.BaseWork;
import org.apache.hadoop.hive.ql.plan.OperatorDesc;
import org.apache.hadoop.hive.ql.plan.ReduceSinkDesc;
import org.apache.hadoop.hive.ql.plan.TableDesc;
import org.apache.hadoop.hive.ql.plan.VectorReduceSinkDesc;
import org.apache.hadoop.hive.ql.plan.VectorReduceSinkInfo;
import org.apache.hadoop.hive.ql.plan.api.OperatorType;
import org.apache.hadoop.hive.serde.serdeConstants;
import org.apache.hadoop.hive.serde2.ByteStream.Output;
import org.apache.hadoop.hive.serde2.binarysortable.BinarySortableSerDe;
import org.apache.hadoop.hive.serde2.binarysortable.fast.BinarySortableSerializeWrite;
import org.apache.hadoop.hive.serde2.lazybinary.fast.LazyBinarySerializeWrite;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfo;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hive.common.util.HashCodeUtil;

/**
 * This class is common operator class for native vectorized reduce sink.
 */
public abstract class VectorReduceSinkCommonOperator extends TerminalOperator<ReduceSinkDesc>
    implements VectorizationContextRegion {

  private static final long serialVersionUID = 1L;
  private static final String CLASS_NAME = VectorReduceSinkCommonOperator.class.getName();
  private static final Log LOG = LogFactory.getLog(CLASS_NAME);

  protected VectorReduceSinkDesc vectorDesc;

  /**
   * Information about our native vectorized reduce sink created by the Vectorizer class during
   * it decision process and useful for execution.
   */
  protected VectorReduceSinkInfo vectorReduceSinkInfo;

  protected VectorizationContext vContext;

  /**
   * Reduce sink key vector expressions.
   */

  // This is map of which vectorized row batch columns are the key columns.
  // And, their types.
  protected int[] reduceSinkKeyColumnMap;
  protected TypeInfo[] reduceSinkKeyTypeInfos;

  // Optional vectorized key expressions that need to be run on each batch.
  protected VectorExpression[] reduceSinkKeyExpressions;

  // This is map of which vectorized row batch columns are the value columns.
  // And, their types.
  protected int[] reduceSinkValueColumnMap;
  protected TypeInfo[] reduceSinkValueTypeInfos;

  // Optional vectorized value expressions that need to be run on each batch.
  protected VectorExpression[] reduceSinkValueExpressions;

  // The above members are initialized by the constructor and must not be
  // transient.
  //---------------------------------------------------------------------------

  // Whether there is to be a tag added to the end of each key and the tag value.
  private transient boolean reduceSkipTag;
  private transient byte reduceTagByte;

  // Binary sortable key serializer.
  protected transient BinarySortableSerializeWrite keyBinarySortableSerializeWrite;

  // The serialized all null key and its hash code.
  private transient byte[] nullBytes;
  private transient int nullKeyHashCode;

  // Lazy binary value serializer.
  private transient LazyBinarySerializeWrite valueLazyBinarySerializeWrite;

  // This helper object serializes LazyBinary format reducer values from columns of a row
  // in a vectorized row batch.
  private transient VectorSerializeRow<LazyBinarySerializeWrite> valueVectorSerializeRow;

  // The output buffer used to serialize a value into.
  private transient Output valueOutput;

  // The hive key and bytes writable value needed to pass the key and value to the collector.
  private transient HiveKey keyWritable;
  private transient BytesWritable valueBytesWritable;

  // Where to write our key and value pairs.
  private transient OutputCollector out;

  // The object that determines equal key series.
  protected transient VectorKeySeriesSerialized serializedKeySeries;

  private transient long numRows = 0;
  private transient long cntr = 1;
  private transient long logEveryNRows = 0;
  private final transient LongWritable recordCounter = new LongWritable();

  // For debug tracing: the name of the map or reduce task.
  protected transient String taskName;

  // Debug display.
  protected transient long batchCounter;

  //---------------------------------------------------------------------------

  /** Kryo ctor. */
  protected VectorReduceSinkCommonOperator() {
    super();
  }

  public VectorReduceSinkCommonOperator(CompilationOpContext ctx) {
    super(ctx);
  }

  public VectorReduceSinkCommonOperator(CompilationOpContext ctx,
      VectorizationContext vContext, OperatorDesc conf) throws HiveException {
    this(ctx);

    ReduceSinkDesc desc = (ReduceSinkDesc) conf;
    this.conf = desc;
    vectorDesc = (VectorReduceSinkDesc) desc.getVectorDesc();
    vectorReduceSinkInfo = vectorDesc.getVectorReduceSinkInfo();
    this.vContext = vContext;

    // Since a key expression can be a calculation and the key will go into a scratch column,
    // we need the mapping and type information.
    reduceSinkKeyColumnMap = vectorReduceSinkInfo.getReduceSinkKeyColumnMap();
    reduceSinkKeyTypeInfos = vectorReduceSinkInfo.getReduceSinkKeyTypeInfos();
    reduceSinkKeyExpressions = vectorReduceSinkInfo.getReduceSinkKeyExpressions();

    reduceSinkValueColumnMap = vectorReduceSinkInfo.getReduceSinkValueColumnMap();
    reduceSinkValueTypeInfos = vectorReduceSinkInfo.getReduceSinkValueTypeInfos();
    reduceSinkValueExpressions = vectorReduceSinkInfo.getReduceSinkValueExpressions();
  }

  // Get the sort order
  private boolean[] getColumnSortOrder(Properties properties, int columnCount) {
    String columnSortOrder = properties.getProperty(serdeConstants.SERIALIZATION_SORT_ORDER);
    boolean[] columnSortOrderIsDesc = new boolean[columnCount];
    if (columnSortOrder == null) {
      Arrays.fill(columnSortOrderIsDesc, false);
    } else {
      for (int i = 0; i < columnSortOrderIsDesc.length; i++) {
        columnSortOrderIsDesc[i] = (columnSortOrder.charAt(i) == '-');
      }
    }
    return columnSortOrderIsDesc;
  }

  private byte[] getColumnNullMarker(Properties properties, int columnCount, boolean[] columnSortOrder) {
    String columnNullOrder = properties.getProperty(serdeConstants.SERIALIZATION_NULL_SORT_ORDER);
    byte[] columnNullMarker = new byte[columnCount];
      for (int i = 0; i < columnNullMarker.length; i++) {
        if (columnSortOrder[i]) {
          // Descending
          if (columnNullOrder != null && columnNullOrder.charAt(i) == 'a') {
            // Null first
            columnNullMarker[i] = BinarySortableSerDe.ONE;
          } else {
            // Null last (default for descending order)
            columnNullMarker[i] = BinarySortableSerDe.ZERO;
          }
        } else {
          // Ascending
          if (columnNullOrder != null && columnNullOrder.charAt(i) == 'z') {
            // Null last
            columnNullMarker[i] = BinarySortableSerDe.ONE;
          } else {
            // Null first (default for ascending order)
            columnNullMarker[i] = BinarySortableSerDe.ZERO;
          }
        }
    }
    return columnNullMarker;
  }

  private byte[] getColumnNotNullMarker(Properties properties, int columnCount, boolean[] columnSortOrder) {
    String columnNullOrder = properties.getProperty(serdeConstants.SERIALIZATION_NULL_SORT_ORDER);
    byte[] columnNotNullMarker = new byte[columnCount];
      for (int i = 0; i < columnNotNullMarker.length; i++) {
        if (columnSortOrder[i]) {
          // Descending
          if (columnNullOrder != null && columnNullOrder.charAt(i) == 'a') {
            // Null first
            columnNotNullMarker[i] = BinarySortableSerDe.ZERO;
          } else {
            // Null last (default for descending order)
            columnNotNullMarker[i] = BinarySortableSerDe.ONE;
          }
        } else {
          // Ascending
          if (columnNullOrder != null && columnNullOrder.charAt(i) == 'z') {
            // Null last
            columnNotNullMarker[i] = BinarySortableSerDe.ZERO;
          } else {
            // Null first (default for ascending order)
            columnNotNullMarker[i] = BinarySortableSerDe.ONE;
          }
        }
    }
    return columnNotNullMarker;
  }

  @Override
  protected void initializeOp(Configuration hconf) throws HiveException {
    super.initializeOp(hconf);

    if (LOG.isDebugEnabled()) {
      // Determine the name of our map or reduce task for debug tracing.
      BaseWork work = Utilities.getMapWork(hconf);
      if (work == null) {
        work = Utilities.getReduceWork(hconf);
      }
      taskName = work.getName();
    }

    String context = hconf.get(Operator.CONTEXT_NAME_KEY, "");
    if (context != null && !context.isEmpty()) {
      context = "_" + context.replace(" ","_");
    }
    statsMap.put(Counter.RECORDS_OUT_INTERMEDIATE + context, recordCounter);

    reduceSkipTag = conf.getSkipTag();
    reduceTagByte = (byte) conf.getTag();

    if (isLogInfoEnabled) {
      LOG.info("Using tag = " + (int) reduceTagByte);
    }

    TableDesc keyTableDesc = conf.getKeySerializeInfo();
    boolean[] columnSortOrder =
        getColumnSortOrder(keyTableDesc.getProperties(), reduceSinkKeyColumnMap.length);
    byte[] columnNullMarker =
        getColumnNullMarker(keyTableDesc.getProperties(), reduceSinkKeyColumnMap.length, columnSortOrder);
    byte[] columnNotNullMarker =
        getColumnNotNullMarker(keyTableDesc.getProperties(), reduceSinkKeyColumnMap.length, columnSortOrder);

    keyBinarySortableSerializeWrite = new BinarySortableSerializeWrite(columnSortOrder,
            columnNullMarker, columnNotNullMarker);

    // Create all nulls key.
    try {
      Output nullKeyOutput = new Output();
      keyBinarySortableSerializeWrite.set(nullKeyOutput);
      for (int i = 0; i < reduceSinkKeyColumnMap.length; i++) {
        keyBinarySortableSerializeWrite.writeNull();
      }
      int nullBytesLength = nullKeyOutput.getLength();
      nullBytes = new byte[nullBytesLength];
      System.arraycopy(nullKeyOutput.getData(), 0, nullBytes, 0, nullBytesLength);
      nullKeyHashCode = HashCodeUtil.calculateBytesHashCode(nullBytes, 0, nullBytesLength);
    } catch (Exception e) {
      throw new HiveException(e);
    }

    valueLazyBinarySerializeWrite = new LazyBinarySerializeWrite(reduceSinkValueColumnMap.length);

    valueVectorSerializeRow =
        new VectorSerializeRow<LazyBinarySerializeWrite>(
            valueLazyBinarySerializeWrite);
    valueVectorSerializeRow.init(reduceSinkValueTypeInfos, reduceSinkValueColumnMap);

    valueOutput = new Output();
    valueVectorSerializeRow.setOutput(valueOutput);

    keyWritable = new HiveKey();

    valueBytesWritable = new BytesWritable();

    batchCounter = 0;
  }

  @Override
  public void process(Object row, int tag) throws HiveException {

    try {
      VectorizedRowBatch batch = (VectorizedRowBatch) row;

      batchCounter++;

      if (batch.size == 0) {
        if (LOG.isDebugEnabled()) {
          LOG.debug(CLASS_NAME + " batch #" + batchCounter + " empty");
        }
        return;
      }

      // Perform any key expressions.  Results will go into scratch columns.
      if (reduceSinkKeyExpressions != null) {
        for (VectorExpression ve : reduceSinkKeyExpressions) {
          ve.evaluate(batch);
        }
      }

      // Perform any value expressions.  Results will go into scratch columns.
      if (reduceSinkValueExpressions != null) {
        for (VectorExpression ve : reduceSinkValueExpressions) {
          ve.evaluate(batch);
        }
      }

      serializedKeySeries.processBatch(batch);

      boolean selectedInUse = batch.selectedInUse;
      int[] selected = batch.selected;

      int keyLength;
      int logical;
      int end;
      int batchIndex;
      do {
        if (serializedKeySeries.getCurrentIsAllNull()) {

          // Use the same logic as ReduceSinkOperator.toHiveKey.
          //
          if (tag == -1 || reduceSkipTag) {
            keyWritable.set(nullBytes, 0, nullBytes.length);
          } else {
            keyWritable.setSize(nullBytes.length + 1);
            System.arraycopy(nullBytes, 0, keyWritable.get(), 0, nullBytes.length);
            keyWritable.get()[nullBytes.length] = reduceTagByte;
          }
          keyWritable.setDistKeyLength(nullBytes.length);
          keyWritable.setHashCode(nullKeyHashCode);

        } else {

          // One serialized key for 1 or more rows for the duplicate keys.
          // LOG.info("reduceSkipTag " + reduceSkipTag + " tag " + tag + " reduceTagByte " + (int) reduceTagByte + " keyLength " + serializedKeySeries.getSerializedLength());
          // LOG.info("process offset " + serializedKeySeries.getSerializedStart() + " length " + serializedKeySeries.getSerializedLength());
          keyLength = serializedKeySeries.getSerializedLength();
          if (tag == -1 || reduceSkipTag) {
            keyWritable.set(serializedKeySeries.getSerializedBytes(),
                serializedKeySeries.getSerializedStart(), keyLength);
          } else {
            keyWritable.setSize(keyLength + 1);
            System.arraycopy(serializedKeySeries.getSerializedBytes(),
                serializedKeySeries.getSerializedStart(), keyWritable.get(), 0, keyLength);
            keyWritable.get()[keyLength] = reduceTagByte;
          }
          keyWritable.setDistKeyLength(keyLength);
          keyWritable.setHashCode(serializedKeySeries.getCurrentHashCode());
        }

        logical = serializedKeySeries.getCurrentLogical();
        end = logical + serializedKeySeries.getCurrentDuplicateCount();
        do {
          batchIndex = (selectedInUse ? selected[logical] : logical);

          valueLazyBinarySerializeWrite.reset();
          valueVectorSerializeRow.serializeWrite(batch, batchIndex);

          valueBytesWritable.set(valueOutput.getData(), 0, valueOutput.getLength());

          collect(keyWritable, valueBytesWritable);
        } while (++logical < end);
  
        if (!serializedKeySeries.next()) {
          break;
        }
      } while (true);

    } catch (Exception e) {
      throw new HiveException(e);
    }
  }

  protected void collect(BytesWritable keyWritable, Writable valueWritable) throws IOException {
    // Since this is a terminal operator, update counters explicitly -
    // forward is not called
    if (null != out) {
      numRows++;
      if (isLogInfoEnabled) {
        if (numRows == cntr) {
          cntr = logEveryNRows == 0 ? cntr * 10 : numRows + logEveryNRows;
          if (cntr < 0 || numRows < 0) {
            cntr = 0;
            numRows = 1;
          }
          LOG.info(toString() + ": records written - " + numRows);
        }
      }

      // BytesWritable valueBytesWritable = (BytesWritable) valueWritable;
      // LOG.info("VectorReduceSinkCommonOperator collect keyWritable " + keyWritable.getLength() + " " +
      //     VectorizedBatchUtil.displayBytes(keyWritable.getBytes(), 0, keyWritable.getLength()) +
      //     " valueWritable " + valueBytesWritable.getLength() +
      //     VectorizedBatchUtil.displayBytes(valueBytesWritable.getBytes(), 0, valueBytesWritable.getLength()));

      out.collect(keyWritable, valueWritable);
    }
  }

  @Override
  protected void closeOp(boolean abort) throws HiveException {
    super.closeOp(abort);
    out = null;
    if (isLogInfoEnabled) {
      LOG.info(toString() + ": records written - " + numRows);
    }
    recordCounter.set(numRows);
  }

  /**
   * @return the name of the operator
   */
  @Override
  public String getName() {
    return getOperatorName();
  }

  static public String getOperatorName() {
    return "RS";
  }

  @Override
  public OperatorType getType() {
    return OperatorType.REDUCESINK;
  }

  @Override
  public VectorizationContext getOuputVectorizationContext() {
    return vContext;
  }

  @Override
  public boolean getIsReduceSink() {
    return true;
  }

  @Override
  public String getReduceOutputName() {
    return conf.getOutputName();
  }

  @Override
  public void setOutputCollector(OutputCollector _out) {
    this.out = _out;
  }
}