/*
 * Copyright 2018-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.r2dbc.mssql.message.token;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.util.AbstractReferenceCounted;
import io.netty.util.ReferenceCountUtil;
import io.r2dbc.mssql.message.type.Length;
import io.r2dbc.mssql.message.type.LengthStrategy;
import io.r2dbc.mssql.message.type.PlpLength;
import io.r2dbc.mssql.util.Assert;
import reactor.util.annotation.Nullable;

import java.nio.Buffer;

/**
 * Row token message containing row bytes.
 * Extends {@link AbstractReferenceCounted} to release associated {@link ByteBuf}s once the row is de-allocated.
 *
 * <p><strong>Note:</strong> PLP values are aggregated in a single {@link ByteBuf} and not yet streamed. This is to be fixed.
 *
 * @author Mark Paluch
 */
public class RowToken extends AbstractReferenceCounted implements DataToken {

    public static final byte TYPE = (byte) 0xD1;

    private final ByteBuf[] data;

    /**
     * Creates a {@link RowToken}.
     *
     * @param data the row data.
     */
    RowToken(ByteBuf[] data) {
        this.data = data;
    }

    /**
     * Decode a {@link RowToken}.
     *
     * @param buffer  the data buffer.
     * @param columns column descriptors.
     * @return the {@link RowToken}.
     */
    public static RowToken decode(ByteBuf buffer, Column[] columns) {

        Assert.requireNonNull(buffer, "Data buffer must not be null");
        Assert.requireNonNull(columns, "List of Columns must not be null");

        return doDecode(buffer, columns);
    }

    /**
     * Check whether the {@link ByteBuf} can be decoded into an entire {@link RowToken}.
     *
     * @param buffer  the data buffer.
     * @param columns column descriptors.
     * @return {@code true} if the buffer contains sufficient data to entirely decode a row.
     */
    public static boolean canDecode(ByteBuf buffer, Column[] columns) {

        Assert.requireNonNull(buffer, "Data buffer must not be null");
        Assert.requireNonNull(columns, "List of Columns must not be null");

        int readerIndex = buffer.readerIndex();

        try {

            for (Column column : columns) {

                if (!canDecodeColumn(buffer, column)) {
                    return false;
                }
            }

            return true;
        } finally {
            buffer.readerIndex(readerIndex);
        }
    }

    static boolean canDecodeColumn(ByteBuf buffer, Column column) {

        if (column.getType().getLengthStrategy() == LengthStrategy.PARTLENTYPE) {
            return canDecodePlp(buffer, column);
        }

        return doCanDecode(buffer, column);
    }

    /**
     * Returns whether the {@link Buffer} with a scalar size can be decoded.
     *
     * @param buffer
     * @param column
     * @return
     */
    private static boolean doCanDecode(ByteBuf buffer, Column column) {

        if (!Length.canDecode(buffer, column.getType())) {
            return false;
        }

        int startRead = buffer.readerIndex();
        Length length = Length.decode(buffer, column.getType());
        int endRead = buffer.readerIndex();

        int descriptorLength = endRead - startRead;
        int dataLength = descriptorLength + length.getLength();
        int adjusted = dataLength - descriptorLength;

        if (buffer.readableBytes() >= adjusted) {
            buffer.skipBytes(adjusted);
            return true;
        }

        return false;
    }

    /**
     * Returns whether a PLP stream can be decoded where can be decoded means that we have received at least the PLP length header.
     *
     * @param buffer data buffer.
     * @param column the related column.
     * @return {@code true} if the PLP sream can be decoded.
     * @see LengthStrategy#PARTLENTYPE
     */
    private static boolean canDecodePlp(ByteBuf buffer, Column column) {

        if (!PlpLength.canDecode(buffer, column.getType())) {
            return false;
        }

        PlpLength totalLength = PlpLength.decode(buffer, column.getType());

        if (totalLength.isNull()) {
            return true;
        }

        while (true) {

            if (!Length.canDecode(buffer, column.getType())) {
                return false;
            }

            Length chunkLength = Length.decode(buffer, column.getType());

            if (chunkLength.getLength() == 0) {
                return true;
            }

            if (buffer.readableBytes() >= chunkLength.getLength()) {
                buffer.skipBytes(chunkLength.getLength());
            } else {
                return false;
            }
        }
    }

    private static RowToken doDecode(ByteBuf buffer, Column[] columns) {

        ByteBuf[] data = new ByteBuf[columns.length];

        for (int i = 0; i < columns.length; i++) {
            data[i] = decodeColumnData(buffer, columns[i]);
        }

        return new RowToken(data);
    }

    /**
     * Decode a {@link ByteBuf data buffer} for a single {@link Column}.
     *
     * @param buffer the data buffer.
     * @param column the column.
     * @return
     */
    @Nullable
    static ByteBuf decodeColumnData(ByteBuf buffer, Column column) {

        if (column.getType().getLengthStrategy() == LengthStrategy.PARTLENTYPE) {
            buffer.markReaderIndex();
            return doDecodePlp(buffer, column);
        } else {
            return doDecode(buffer, column);
        }
    }

    /**
     * Decode a scalar length value. Returns {@code null} if {@link Length#isNull()}.
     *
     * @param buffer the data buffer.
     * @param column the column.
     * @return
     */
    @Nullable
    private static ByteBuf doDecode(ByteBuf buffer, Column column) {

        int startRead = buffer.readerIndex();
        Length length = Length.decode(buffer, column.getType());

        if (length.isNull()) {
            return null;
        }

        int endRead = buffer.readerIndex();
        int descriptorLength = endRead - startRead;

        buffer.readerIndex(startRead);
        return buffer.readRetainedSlice(descriptorLength + length.getLength());
    }

    /**
     * Decode a PLP stream value. Returns {@code null} if {@link Length#isNull()}. The decoded value contains an entire PLP token stream with chunk headers.
     *
     * @param buffer the data buffer.
     * @param column the column.
     * @return
     */
    @Nullable
    private static ByteBuf doDecodePlp(ByteBuf buffer, Column column) {

        PlpLength totalLength = PlpLength.decode(buffer, column.getType());

        if (totalLength.isNull()) {
            return null;
        }

        CompositeByteBuf plpData = buffer.alloc().compositeBuffer();
        ByteBuf length = buffer.alloc().buffer(8);
        totalLength.encode(length);

        plpData.addComponent(true, length);

        while (true) {

            Length chunkLength = Length.decode(buffer, column.getType());

            if (chunkLength.getLength() == 0) {
                break;
            }

            length = buffer.alloc().buffer(4);
            chunkLength.encode(length, column.getType());

            plpData.addComponent(true, length);
            plpData.addComponent(true, buffer.readRetainedSlice(chunkLength.getLength()));
        }

        return plpData;
    }

    /**
     * Returns the {@link ByteBuf data} for the column at {@code index}.
     *
     * @param index the column {@code index}.
     * @return the data buffer. Can be {@code null} if indicated by null-bit compression.
     */
    @Nullable
    public ByteBuf getColumnData(int index) {
        return this.data[index];
    }

    @Override
    public byte getType() {
        return TYPE;
    }

    @Override
    public String getName() {
        return "ROW";
    }

    @Override
    public RowToken touch(Object hint) {
        return this;
    }

    @Override
    protected void deallocate() {

        for (ByteBuf datum : this.data) {
            ReferenceCountUtil.release(datum);
        }
    }

}
