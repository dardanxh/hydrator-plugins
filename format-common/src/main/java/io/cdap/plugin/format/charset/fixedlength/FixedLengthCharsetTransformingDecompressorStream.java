/*
 * Copyright © 2020 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.cdap.plugin.format.charset.fixedlength;

import io.cdap.plugin.format.charset.CharsetTransformingLineRecordReader;
import org.apache.hadoop.io.compress.DecompressorStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

/**
 * DecompressorStream implementation for the FixedLengthCharsetTransformingDecompressor.
 * <p>
 * This can be used to convert input streams containing bytes for fixed length charsets into UTF-8 bytes.
 */
public class FixedLengthCharsetTransformingDecompressorStream extends DecompressorStream {
  private static final Logger LOG = LoggerFactory.getLogger(FixedLengthCharsetTransformingDecompressorStream.class);

  //Starting and ending position in the file.
  protected final long start;
  protected final long end;
  protected final FixedLengthCharset fixedLengthCharset;

  protected FixedLengthCharsetTransformingDecompressorStream(InputStream in,
                                                             FixedLengthCharset fixedLengthCharset,
                                                             long start,
                                                             long end)
    throws IOException {
    super(in, new FixedLengthCharsetTransformingDecompressor(fixedLengthCharset));
    long skippedBytes = in.skip(start);
    this.fixedLengthCharset = fixedLengthCharset;
    this.start = start;
    this.end = end;
  }

  @Override
  protected int decompress(byte[] b, int off, int len) throws IOException {
    //Set input for decompression if it's needed for execution.
    if (this.decompressor.needsInput()) {
      int l = getCompressedData();
      if (l > 0) {
        this.decompressor.setInput(buffer, 0, l);
      }
    }

    //Proceed with super method.
    return super.decompress(b, off, len);
  }

  @Override
  public long getPos() throws IOException {
    //If we're working with a Charset Transforming decompressor, we can calculate the current position on the input file
    // By adding the starting position in the file with the number of bytes we have read so far.
    if (this.decompressor instanceof FixedLengthCharsetTransformingDecompressor) {
      FixedLengthCharsetTransformingDecompressor flcDecompressor =
        (FixedLengthCharsetTransformingDecompressor) this.decompressor;

      //Actual position is starting possition + the number of bytes we have consumed.
      return start + flcDecompressor.getNumConsumedBytes();
    } else {
      return super.getPos();
    }
  }

  /**
   * Fill the input buffer with data from the source input.
   *
   * Partition boundaries present a challenge: We need to make sure to read just enough characters to make it into
   * the next complete line.
   *
   * Note that, as we approach the partition boundary, we start reading one character at a time until the input
   * reader is able to read a full line.
   *
   * The method that reads a full line after the partition boundary can be found in
   * {@link CharsetTransformingLineRecordReader#nextKeyValue()}
   *
   * @return Number of bytes read from the source.
   * @throws IOException when there is a problema reading from the underlying stream.
   */
  @Override
  protected int getCompressedData() throws IOException {
    if (this.decompressor instanceof FixedLengthCharsetTransformingDecompressor) {
      checkStream();

      FixedLengthCharsetTransformingDecompressor flcDecompressor =
        (FixedLengthCharsetTransformingDecompressor) this.decompressor;

      // Calculate the number of bytes in this partition that we still need to read.
      long bytesUntilPartitionBoundary = this.end - (this.start + flcDecompressor.getNumConsumedBytes());

      // Prevent int overflow
      if (bytesUntilPartitionBoundary > Integer.MAX_VALUE) {
        bytesUntilPartitionBoundary = Integer.MAX_VALUE;
      }

      // If we are at or after the partiton boundary, we read 1 character at a time until we're able to
      // read a full line.
      if (bytesUntilPartitionBoundary < this.fixedLengthCharset.getNumBytesPerCharacter()) {
        bytesUntilPartitionBoundary = this.fixedLengthCharset.getNumBytesPerCharacter();
      }

      // Calculate how many bytes we actually need to read
      int readLength = Math.min(buffer.length, (int) bytesUntilPartitionBoundary);

      return in.read(buffer, 0, readLength);
    } else {
      return super.getCompressedData();
    }
  }
}