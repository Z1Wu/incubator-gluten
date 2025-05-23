/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.gluten.vectorized;

import org.apache.gluten.exception.GlutenException;

import org.apache.spark.sql.execution.utils.CHExecUtil;
import org.apache.spark.sql.vectorized.ColumnVector;
import org.apache.spark.sql.vectorized.ColumnarBatch;

public class CHNativeBlock {
  private long blockAddress;

  public CHNativeBlock(long blockAddress) {
    this.blockAddress = blockAddress;
  }

  public static CHNativeBlock fromColumnarBatch(ColumnarBatch batch) {
    if (batch.numCols() == 0 || !(batch.column(0) instanceof CHColumnVector)) {
      throw new GlutenException(
          "Unexpected ColumnarBatch: "
              + (batch.numCols() == 0
                  ? "0 column"
                  : "expected CHColumnVector, but " + batch.column(0).getClass()));
    }
    CHColumnVector columnVector = (CHColumnVector) batch.column(0);
    return new CHNativeBlock(columnVector.getBlockAddress());
  }

  private native int nativeNumRows(long blockAddress);

  public int numRows() {
    return nativeNumRows(blockAddress);
  }

  public long blockAddress() {
    return blockAddress;
  }

  private native int nativeNumColumns(long blockAddress);

  public int numColumns() {
    return nativeNumColumns(blockAddress);
  }

  private native byte[] nativeColumnType(long blockAddress, int position);

  public byte[] getTypeByPosition(int position) {
    return nativeColumnType(blockAddress, position);
  }

  private native long nativeTotalBytes(long blockAddress);

  public long totalBytes() {
    return nativeTotalBytes(blockAddress);
  }

  public native void nativeClose(long blockAddress);

  public native BlockStats nativeBlockStats(long blockAddress, int columnPosition);

  public BlockStats getBlockStats(int columnPosition) {
    return nativeBlockStats(blockAddress, columnPosition);
  }

  public static native long copyBlock(long blockAddress);

  public ColumnarBatch copyColumnarBatch() {
    return new CHNativeBlock(copyBlock(blockAddress)).toColumnarBatch();
  }

  private native long nativeSlice(long blockAddress, int offset, int limit);

  public long nativeSlice(int offset, int limit) {
    return nativeSlice(blockAddress, offset, limit);
  }

  public void close() {
    if (blockAddress != 0) {
      nativeClose(blockAddress);
      blockAddress = 0;
    }
  }

  public static void closeFromColumnarBatch(ColumnarBatch cb) {
    if (cb != null) {
      if (cb.numCols() > 0) {
        CHColumnVector col = (CHColumnVector) cb.column(0);
        CHNativeBlock block = new CHNativeBlock(col.getBlockAddress());
        block.close();
      }
      cb.close();
    }
  }

  public ColumnarBatch toColumnarBatch() {
    int numRows = numRows();
    int cols = numColumns();
    ColumnVector[] vectors = new ColumnVector[cols];
    for (int i = 0; i < cols; i++) {
      vectors[i] =
          new CHColumnVector(CHExecUtil.inferSparkDataType(getTypeByPosition(i)), blockAddress, i);
    }
    return new ColumnarBatch(vectors, numRows);
  }

  public static ColumnarBatch slice(ColumnarBatch batch, int offset, int limit) {
    if (offset + limit > batch.numRows()) {
      throw new GlutenException(
          "Parameter out of bound in slice function, offset: " + offset + ", limit: " + limit);
    }
    CHNativeBlock block = CHNativeBlock.fromColumnarBatch(batch);
    long blockAddress = block.nativeSlice(offset, limit);
    return new CHNativeBlock(blockAddress).toColumnarBatch();
  }
}
