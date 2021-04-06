/*
 * Copyright 2019-2021 the original author or authors.
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

package io.r2dbc.mssql.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.r2dbc.mssql.message.tds.Encode;
import io.r2dbc.mssql.message.tds.ServerCharset;
import io.r2dbc.mssql.message.type.Length;
import io.r2dbc.mssql.message.type.LengthStrategy;
import io.r2dbc.mssql.message.type.PlpLength;
import io.r2dbc.mssql.message.type.SqlServerType;
import io.r2dbc.mssql.message.type.TypeInformation;
import io.r2dbc.mssql.util.EncodedAssert;
import io.r2dbc.mssql.util.HexUtils;
import io.r2dbc.mssql.util.TestByteBufAllocator;
import io.r2dbc.spi.Clob;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;

import static io.r2dbc.mssql.message.type.TypeInformation.builder;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ClobCodec}.
 *
 * @author Mark Paluch
 */
class ClobCodecUnitTests {

    @Test
    void shouldEncodeNull() {

        Encoded encoded = ClobCodec.INSTANCE.encodeNull(TestByteBufAllocator.TEST);

        EncodedAssert.assertThat(encoded).isEqualToHex("40 1f 00 00 00 00 00 ff ff");
        assertThat(encoded.getFormalType()).isEqualTo("varchar(8000)");
    }

    @Test
    void shouldBeAbleToEncodeNull() {

        assertThat(ClobCodec.INSTANCE.canEncodeNull(Clob.class)).isTrue();
    }

    @Test
    void shouldBeAbleToDecodeChar() {

        TypeInformation binary =
            builder().withServerType(SqlServerType.CHAR).withLengthStrategy(LengthStrategy.USHORTLENTYPE).build();

        assertThat(ClobCodec.INSTANCE.canDecode(ColumnUtil.createColumn(binary), Clob.class)).isTrue();
    }

    @Test
    void shouldBeAbleToDecodeVarVarChar() {

        TypeInformation varchar =
            builder().withServerType(SqlServerType.VARCHAR).withLengthStrategy(LengthStrategy.USHORTLENTYPE).build();

        assertThat(ClobCodec.INSTANCE.canDecode(ColumnUtil.createColumn(varchar), Clob.class)).isTrue();
    }

    @Test
    void shouldBeAbleToDecodeVarCharMax() {

        TypeInformation binary =
            builder().withServerType(SqlServerType.VARCHARMAX).withLengthStrategy(LengthStrategy.PARTLENTYPE).build();

        assertThat(ClobCodec.INSTANCE.canDecode(ColumnUtil.createColumn(binary), Clob.class)).isTrue();
    }

    @Test
    void shouldBeAbleToDecodeText() {

        TypeInformation binary =
            builder().withServerType(SqlServerType.TEXT).withLengthStrategy(LengthStrategy.PARTLENTYPE).build();

        assertThat(ClobCodec.INSTANCE.canDecode(ColumnUtil.createColumn(binary), Clob.class)).isTrue();
    }

    @Test
    void shouldBeAbleToDecodeNtext() {

        TypeInformation binary =
            builder().withServerType(SqlServerType.NTEXT).withLengthStrategy(LengthStrategy.PARTLENTYPE).build();

        assertThat(ClobCodec.INSTANCE.canDecode(ColumnUtil.createColumn(binary), Clob.class)).isTrue();
    }

    @Test
    void shouldBeAbleToDecodeVarchar() {

        TypeInformation varchar =
            builder().withServerType(SqlServerType.VARCHAR).withLengthStrategy(LengthStrategy.USHORTLENTYPE).withCharset(ServerCharset.CP1252.charset()).build();

        ByteBuf data = TestByteBufAllocator.TEST.buffer();
        Encode.uShort(data, 6);
        data.writeCharSequence("foobar", ServerCharset.CP1252.charset());

        Clob clob = ClobCodec.INSTANCE.decode(data, ColumnUtil.createColumn(varchar), Clob.class);

        StepVerifier.create(clob.stream()).expectNext("foobar").verifyComplete();
    }

    @Test
    void shouldBeAbleToDecodePlpStream() {

        TypeInformation varchar =
            builder().withServerType(SqlServerType.VARCHAR).withLengthStrategy(LengthStrategy.PARTLENTYPE).withCharset(StandardCharsets.US_ASCII).build();

        ByteBuf buffer = TestByteBufAllocator.TEST.heapBuffer(12 + 24);
        PlpLength.of(24).encode(buffer);

        Length.of(8).encode(buffer, varchar);
        buffer.writeBytes("C1xxxxxx".getBytes());

        Length.of(8).encode(buffer, varchar);
        buffer.writeBytes("C2yyyyyy".getBytes());

        Length.of(8).encode(buffer, varchar);
        buffer.writeBytes("C3zzzzzz".getBytes());

        Clob clob = ClobCodec.INSTANCE.decode(buffer, ColumnUtil.createColumn(varchar), Clob.class);

        StepVerifier.create(clob.stream())
            .expectNext("C1xxxxxx")
            .expectNext("C2yyyyyy")
            .expectNext("C3zzzzzz")
            .verifyComplete();
    }

    @Test
    void shouldReleaseConsumedBuffers() {

        TypeInformation varchar =
            builder().withServerType(SqlServerType.VARCHAR).withLengthStrategy(LengthStrategy.PARTLENTYPE).withCharset(StandardCharsets.US_ASCII).build();

        PooledByteBufAllocator alloc = PooledByteBufAllocator.DEFAULT;

        ByteBuf buffer = alloc.buffer();
        PlpLength.of(8).encode(buffer);

        Length.of(8).encode(buffer, varchar);
        buffer.writeBytes("C1xxxxxx".getBytes());

        Clob clob = ClobCodec.INSTANCE.decode(buffer, ColumnUtil.createColumn(varchar), Clob.class);
        buffer.release();

        StepVerifier.create(clob.stream())
            .expectNextCount(1)
            .verifyComplete();

        assertThat(buffer.refCnt()).isZero();
    }

    @Test
    void shouldReleaseEmptyBuffer() {

        TypeInformation type =
            builder().withMaxLength(50).withLengthStrategy(LengthStrategy.PARTLENTYPE).withPrecision(50).withServerType(SqlServerType.NVARCHARMAX).withCharset(StandardCharsets.UTF_16LE).build();

        ByteBuf data = HexUtils.decodeToByteBuf("00 00 00 00 00 00 00 00");

        Clob clob = ClobCodec.INSTANCE.decode(data, ColumnUtil.createColumn(type), Clob.class);
        data.release();

        Flux.from(clob.stream())
            .as(StepVerifier::create)
            .verifyComplete();

        assertThat(data.refCnt()).isZero();
    }

    @Test
    void shouldDecodeVarcharMaxSplitCharacter() {

        TypeInformation type =
            builder().withMaxLength(50).withLengthStrategy(LengthStrategy.PARTLENTYPE).withPrecision(50).withServerType(SqlServerType.NVARCHARMAX).withCharset(StandardCharsets.UTF_16LE).build();

        ByteBuf data = HexUtils.decodeToByteBuf("62 00 00 00 00 00 00 00 " +
            "2d 00 00 00 " +
            "6c 00 65 00 61 00 6e 00 6e 00 65 00 2e 00 61 00 73 00 68 00 74 00 6f 00 6e 00 40 00 64 00 64 00 2d 00 70 00 75 00 62 00 2e 00 63 00 6f " +
            "35 00 00 00 " +
            "00 6d 00 2c 00 64 00 61 00 76 00 69 00 64 00 2e 00 6d 00 61 00 61 00 73 00 73 00 65 00 6e 00 40 00 64 00 64 00 2d 00 70 00 75 00 62 00 2e 00 63 00 6f 00 6d 00 " +
            "00 00 00 00");

        Clob clob = ClobCodec.INSTANCE.decode(data, ColumnUtil.createColumn(type), Clob.class);
        data.release();

        Flux.from(clob.stream())
            .as(StepVerifier::create)
            .expectNext("leanne.ashton@dd-pub.c")
            .expectNext("om,david.maassen@dd-pub.com")
            .verifyComplete();

        assertThat(data.refCnt()).isZero();
    }

    @Test
    void shouldReportRemainderInDecodeBuffer() {

        TypeInformation type =
            builder().withMaxLength(50).withLengthStrategy(LengthStrategy.PARTLENTYPE).withPrecision(50).withServerType(SqlServerType.NVARCHARMAX).withCharset(StandardCharsets.UTF_16LE).build();

        ByteBuf data = HexUtils.decodeToByteBuf("2d 00 00 00 00 00 00 00 " +
            "2d 00 00 00 " +
            "6c 00 65 00 61 00 6e 00 6e 00 65 00 2e 00 61 00 73 00 68 00 74 00 6f 00 6e 00 40 00 64 00 64 00 2d 00 70 00 75 00 62 00 2e 00 63 00 6f");

        Clob clob = ClobCodec.INSTANCE.decode(data, ColumnUtil.createColumn(type), Clob.class);
        data.release();

        Flux.from(clob.stream())
            .as(StepVerifier::create)
            .expectNext("leanne.ashton@dd-pub.c")
            .verifyError(ClobCodec.ClobDecodeException.class);

        assertThat(data.refCnt()).isZero();
    }

    @Test
    void shouldReleaseRemainingBuffersOnCancel() {

        TypeInformation varchar =
            builder().withServerType(SqlServerType.VARCHAR).withLengthStrategy(LengthStrategy.PARTLENTYPE).withCharset(StandardCharsets.US_ASCII).build();

        ByteBuf buffer = TestByteBufAllocator.TEST.buffer(12 + 24);
        PlpLength.of(24).encode(buffer);

        Length.of(8).encode(buffer, varchar);
        buffer.writeBytes("C1xxxxxx".getBytes());

        Length.of(8).encode(buffer, varchar);
        buffer.writeBytes("C2yyyyyy".getBytes());

        Length.of(8).encode(buffer, varchar);
        buffer.writeBytes("C3zzzzzz".getBytes());

        Clob clob = ClobCodec.INSTANCE.decode(buffer, ColumnUtil.createColumn(varchar), Clob.class);
        buffer.release();

        assertThat(buffer.refCnt()).isEqualTo(1);

        StepVerifier.create(clob.stream(), 0)
            .thenRequest(1)
            .expectNextCount(1)
            .thenCancel()
            .verify();

        assertThat(buffer.refCnt()).isZero();
    }

    @Test
    void shouldReleaseOnDiscard() {

        TypeInformation varchar =
            builder().withServerType(SqlServerType.VARCHAR).withLengthStrategy(LengthStrategy.PARTLENTYPE).withCharset(StandardCharsets.US_ASCII).build();

        PooledByteBufAllocator alloc = PooledByteBufAllocator.DEFAULT;

        ByteBuf buffer = alloc.buffer();
        PlpLength.of(8).encode(buffer);

        Length.of(8).encode(buffer, varchar);
        buffer.writeBytes("C1xxxxxx".getBytes());

        Clob clob = ClobCodec.INSTANCE.decode(buffer, ColumnUtil.createColumn(varchar), Clob.class);
        buffer.release();

        StepVerifier.create(clob.discard())
            .verifyComplete();

        assertThat(buffer.refCnt()).isZero();
    }
}
