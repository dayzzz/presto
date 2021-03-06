/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.orc;

import com.facebook.presto.spi.type.DecimalType;
import com.facebook.presto.spi.type.SqlDate;
import com.facebook.presto.spi.type.SqlDecimal;
import com.facebook.presto.spi.type.SqlTimestamp;
import com.facebook.presto.spi.type.SqlVarbinary;
import com.google.common.base.Function;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.AbstractSequentialIterator;
import com.google.common.collect.ContiguousSet;
import com.google.common.collect.DiscreteDomain;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Range;
import com.google.common.primitives.Shorts;
import org.apache.hadoop.hive.common.type.HiveDecimal;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.JavaHiveDecimalObjectInspector;
import org.apache.hadoop.hive.serde2.typeinfo.DecimalTypeInfo;
import org.joda.time.DateTimeZone;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.sql.Date;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static com.facebook.presto.orc.OrcTester.HIVE_STORAGE_TIME_ZONE;
import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spi.type.BooleanType.BOOLEAN;
import static com.facebook.presto.spi.type.DateType.DATE;
import static com.facebook.presto.spi.type.DoubleType.DOUBLE;
import static com.facebook.presto.spi.type.TimeZoneKey.UTC_KEY;
import static com.facebook.presto.spi.type.TimestampType.TIMESTAMP;
import static com.facebook.presto.spi.type.VarbinaryType.VARBINARY;
import static com.facebook.presto.spi.type.VarcharType.VARCHAR;
import static com.google.common.base.Functions.compose;
import static com.google.common.collect.Iterables.concat;
import static com.google.common.collect.Iterables.cycle;
import static com.google.common.collect.Iterables.limit;
import static com.google.common.collect.Iterables.transform;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaBooleanObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaByteArrayObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaByteObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaDateObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaDoubleObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaFloatObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaIntObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaLongObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaShortObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaStringObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaTimestampObjectInspector;
import static org.testng.Assert.assertEquals;

public abstract class AbstractTestOrcReader
{
    private static final JavaHiveDecimalObjectInspector DECIMAL_INSPECTOR_PRECISION_2 =
            new JavaHiveDecimalObjectInspector(new DecimalTypeInfo(2, 1));
    private static final JavaHiveDecimalObjectInspector DECIMAL_INSPECTOR_PRECISION_4 =
            new JavaHiveDecimalObjectInspector(new DecimalTypeInfo(4, 2));
    private static final JavaHiveDecimalObjectInspector DECIMAL_INSPECTOR_PRECISION_8 =
            new JavaHiveDecimalObjectInspector(new DecimalTypeInfo(8, 4));
    private static final JavaHiveDecimalObjectInspector DECIMAL_INSPECTOR_PRECISION_17 =
            new JavaHiveDecimalObjectInspector(new DecimalTypeInfo(17, 8));
    private static final JavaHiveDecimalObjectInspector DECIMAL_INSPECTOR_PRECISION_18 =
            new JavaHiveDecimalObjectInspector(new DecimalTypeInfo(18, 8));
    private static final JavaHiveDecimalObjectInspector DECIMAL_INSPECTOR_PRECISION_38 =
            new JavaHiveDecimalObjectInspector(new DecimalTypeInfo(38, 16));

    private static final DecimalType DECIMAL_TYPE_PRECISION_2 = DecimalType.createDecimalType(2, 1);
    private static final DecimalType DECIMAL_TYPE_PRECISION_4 = DecimalType.createDecimalType(4, 2);
    private static final DecimalType DECIMAL_TYPE_PRECISION_8 = DecimalType.createDecimalType(8, 4);
    private static final DecimalType DECIMAL_TYPE_PRECISION_17 = DecimalType.createDecimalType(17, 8);
    private static final DecimalType DECIMAL_TYPE_PRECISION_18 = DecimalType.createDecimalType(18, 8);
    private static final DecimalType DECIMAL_TYPE_PRECISION_38 = DecimalType.createDecimalType(38, 16);

    private final OrcTester tester;

    public AbstractTestOrcReader(OrcTester tester)
    {
        this.tester = tester;
    }

    @BeforeClass
    public void setUp()
    {
        assertEquals(DateTimeZone.getDefault(), HIVE_STORAGE_TIME_ZONE);
    }

    @Test
    public void testBooleanSequence()
            throws Exception
    {
        tester.testRoundTrip(javaBooleanObjectInspector, limit(cycle(ImmutableList.of(true, false, false)), 30_000), BOOLEAN);
    }

    @Test
    public void testLongSequence()
            throws Exception
    {
        testRoundTripNumeric(intsBetween(0, 31_234));
    }

    @Test
    public void testLongSequenceWithHoles()
            throws Exception
    {
        testRoundTripNumeric(skipEvery(5, intsBetween(0, 31_234)));
    }

    @Test
    public void testLongDirect()
            throws Exception
    {
        testRoundTripNumeric(limit(cycle(ImmutableList.of(1, 3, 5, 7, 11, 13, 17)), 30_000));
    }

    @Test
    public void testLongDirect2()
            throws Exception
    {
        List<Integer> values = new ArrayList<>(31_234);
        for (int i = 0; i < 31_234; i++) {
            values.add(i);
        }
        Collections.shuffle(values, new Random(0));
        testRoundTripNumeric(values);
    }

    @Test
    public void testLongShortRepeat()
            throws Exception
    {
        testRoundTripNumeric(limit(repeatEach(4, cycle(ImmutableList.of(1, 3, 5, 7, 11, 13, 17))), 30_000));
    }

    @Test
    public void testLongPatchedBase()
            throws Exception
    {
        testRoundTripNumeric(limit(cycle(concat(intsBetween(0, 18), ImmutableList.of(30_000, 20_000))), 30_000));
    }

    @Test
    public void testLongStrideDictionary()
            throws Exception
    {
        testRoundTripNumeric(concat(ImmutableList.of(1), Collections.nCopies(9999, 123), ImmutableList.of(2), Collections.nCopies(9999, 123)));
    }

    private void testRoundTripNumeric(Iterable<Integer> writeValues)
            throws Exception
    {
        tester.testRoundTrip(javaByteObjectInspector,
                transform(writeValues, AbstractTestOrcReader::intToByte),
                AbstractTestOrcReader::byteToLong,
                BIGINT);

        tester.testRoundTrip(javaShortObjectInspector,
                transform(writeValues, AbstractTestOrcReader::intToShort),
                AbstractTestOrcReader::shortToLong,
                BIGINT);

        tester.testRoundTrip(javaIntObjectInspector, writeValues, AbstractTestOrcReader::intToLong, BIGINT);
        tester.testRoundTrip(javaLongObjectInspector, transform(writeValues, AbstractTestOrcReader::intToLong), BIGINT);

        tester.testRoundTrip(javaTimestampObjectInspector,
                transform(writeValues, AbstractTestOrcReader::intToTimestamp),
                transform(writeValues, AbstractTestOrcReader::intToSqlTimestamp),
                TIMESTAMP);

        tester.testRoundTrip(javaDateObjectInspector,
                transform(writeValues, AbstractTestOrcReader::intToDate),
                transform(writeValues, AbstractTestOrcReader::intToSqlDate),
                DATE);
    }

    @Test
    public void testFloatSequence()
            throws Exception
    {
        tester.testRoundTrip(javaFloatObjectInspector, floatSequence(0.0f, 0.1f, 30_000), AbstractTestOrcReader::floatToDouble, DOUBLE);
    }

    @Test
    public void testDoubleSequence()
            throws Exception
    {
        tester.testRoundTrip(javaDoubleObjectInspector, doubleSequence(0, 0.1, 30_000), DOUBLE);
    }

    @Test
    public void testDecimalSequence()
            throws Exception
    {
        tester.testRoundTrip(DECIMAL_INSPECTOR_PRECISION_2, decimalSequence("0.0", "0.1", 30, 1), toSqlDecimal(1), DECIMAL_TYPE_PRECISION_2);
        tester.testRoundTrip(DECIMAL_INSPECTOR_PRECISION_4, decimalSequence("00.00", "00.01", 30_00, 2), toSqlDecimal(2), DECIMAL_TYPE_PRECISION_4);
        tester.testRoundTrip(DECIMAL_INSPECTOR_PRECISION_8, decimalSequence("0000.0000", "0000.0100", 30_000, 4), toSqlDecimal(4), DECIMAL_TYPE_PRECISION_8);
        tester.testRoundTrip(DECIMAL_INSPECTOR_PRECISION_17, decimalSequence("000000000.00000000", "000000000.01000000", 30_000, 8), toSqlDecimal(8), DECIMAL_TYPE_PRECISION_17);
        tester.testRoundTrip(DECIMAL_INSPECTOR_PRECISION_18, decimalSequence("0000000000.00000000", "0000000000.01000000", 30_000, 8), toSqlDecimal(8), DECIMAL_TYPE_PRECISION_18);
        tester.testRoundTrip(DECIMAL_INSPECTOR_PRECISION_38, decimalSequence("0.0000000000000000", "0.0100000000000000", 30_000, 16), toSqlDecimal(16), DECIMAL_TYPE_PRECISION_38);
    }

    @Test
    public void testDoubleNaNInfinity()
            throws Exception
    {
        tester.testRoundTrip(javaDoubleObjectInspector, ImmutableList.of(1000.0, -1.0, Double.POSITIVE_INFINITY), DOUBLE);
        tester.testRoundTrip(javaDoubleObjectInspector, ImmutableList.of(-1000.0, Double.NEGATIVE_INFINITY, 1.0), DOUBLE);
        tester.testRoundTrip(javaDoubleObjectInspector, ImmutableList.of(0.0, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY), DOUBLE);

        tester.testRoundTrip(javaDoubleObjectInspector, ImmutableList.of(Double.NaN, -1.0, 1.0), DOUBLE);
        tester.testRoundTrip(javaDoubleObjectInspector, ImmutableList.of(Double.NaN, -1.0, Double.POSITIVE_INFINITY), DOUBLE);
        tester.testRoundTrip(javaDoubleObjectInspector, ImmutableList.of(Double.NaN, Double.NEGATIVE_INFINITY, 1.0), DOUBLE);
        tester.testRoundTrip(javaDoubleObjectInspector, ImmutableList.of(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY), DOUBLE);
    }

    @Test
    public void testStringUnicode()
            throws Exception
    {
        tester.testRoundTrip(javaStringObjectInspector, limit(cycle(ImmutableList.of("apple", "apple pie", "apple\uD835\uDC03", "apple\uFFFD")), 30_000), VARCHAR);
    }

    @Test
    public void testStringDirectSequence()
            throws Exception
    {
        tester.testRoundTrip(javaStringObjectInspector, transform(intsBetween(0, 30_000), Object::toString), VARCHAR);
    }

    @Test
    public void testStringDictionarySequence()
            throws Exception
    {
        tester.testRoundTrip(javaStringObjectInspector, limit(cycle(transform(ImmutableList.of(1, 3, 5, 7, 11, 13, 17), Object::toString)), 30_000), VARCHAR);
    }

    @Test
    public void testStringStrideDictionary()
            throws Exception
    {
        tester.testRoundTrip(javaStringObjectInspector, concat(ImmutableList.of("a"), Collections.nCopies(9999, "123"), ImmutableList.of("b"), Collections.nCopies(9999, "123")), VARCHAR);
    }

    @Test
    public void testEmptyStringSequence()
            throws Exception
    {
        tester.testRoundTrip(javaStringObjectInspector, limit(cycle(""), 30_000), VARCHAR);
    }

    @Test
    public void testBinaryDirectSequence()
            throws Exception
    {
        Iterable<byte[]> writeValues = transform(intsBetween(0, 30_000), compose(AbstractTestOrcReader::stringToByteArray, Object::toString));
        tester.testRoundTrip(javaByteArrayObjectInspector,
                writeValues,
                transform(writeValues, AbstractTestOrcReader::byteArrayToVarbinary),
                VARBINARY);
    }

    @Test
    public void testBinaryDictionarySequence()
            throws Exception
    {
        Iterable<byte[]> writeValues = limit(cycle(transform(ImmutableList.of(1, 3, 5, 7, 11, 13, 17), compose(AbstractTestOrcReader::stringToByteArray, Object::toString))), 30_000);
        tester.testRoundTrip(javaByteArrayObjectInspector,
                writeValues,
                transform(writeValues, AbstractTestOrcReader::byteArrayToVarbinary),
                VARBINARY);
    }

    @Test
    public void testEmptyBinarySequence()
            throws Exception
    {
        tester.testRoundTrip(javaByteArrayObjectInspector, limit(cycle(new byte[0]), 30_000), AbstractTestOrcReader::byteArrayToVarbinary, VARBINARY);
    }

    @Test
    public void testDwrfInvalidCheckpointsForRowGroupDictionary()
            throws Exception
    {
        Iterable<Integer> values = limit(cycle(concat(
                        ImmutableList.of(1), Collections.nCopies(9999, 123),
                        ImmutableList.of(2), Collections.nCopies(9999, 123),
                        ImmutableList.of(3), Collections.nCopies(9999, 123),
                        Collections.nCopies(1_000_000, null))),
                200_000);

        tester.assertRoundTrip(javaIntObjectInspector, values, transform(values, value -> value == null ? null : (long) value), BIGINT);

        Iterable<String> stringValue = transform(values, value -> value == null ? null : String.valueOf(value));
        tester.assertRoundTrip(javaStringObjectInspector, stringValue, stringValue, VARCHAR);
    }

    @Test
    public void testDwrfInvalidCheckpointsForStripeDictionary()
            throws Exception
    {
        Iterable<String> values = limit(cycle(transform(ImmutableList.of(1, 3, 5, 7, 11, 13, 17), Object::toString)), 200_000);
        tester.testRoundTrip(javaStringObjectInspector, values, VARCHAR);
    }

    private static <T> Iterable<T> skipEvery(int n, Iterable<T> iterable)
    {
        return () -> new AbstractIterator<T>()
        {
            private final Iterator<T> delegate = iterable.iterator();
            private int position;

            @Override
            protected T computeNext()
            {
                while (true) {
                    if (!delegate.hasNext()) {
                        return endOfData();
                    }

                    T next = delegate.next();
                    position++;
                    if (position <= n) {
                        return next;
                    }
                    position = 0;
                }
            }
        };
    }

    private static <T> Iterable<T> repeatEach(int n, Iterable<T> iterable)
    {
        return () -> new AbstractIterator<T>()
        {
            private final Iterator<T> delegate = iterable.iterator();
            private int position;
            private T value;

            @Override
            protected T computeNext()
            {
                if (position == 0) {
                    if (!delegate.hasNext()) {
                        return endOfData();
                    }
                    value = delegate.next();
                }

                position++;
                if (position >= n) {
                    position = 0;
                }
                return value;
            }
        };
    }

    private static Iterable<Float> floatSequence(double start, double step, int items)
    {
        return transform(doubleSequence(start, step, items), input -> {
            if (input == null) {
                return null;
            }
            return input.floatValue();
        });
    }

    private static Iterable<Double> doubleSequence(double start, double step, int items)
    {
        return () -> new AbstractSequentialIterator<Double>(start)
        {
            private int item;

            @Override
            protected Double computeNext(Double previous)
            {
                if (item >= items) {
                    return null;
                }
                item++;
                return previous + step;
            }
        };
    }

    private static Iterable<HiveDecimal> decimalSequence(String start, String step, int items, int scale)
    {
        HiveDecimal hiveStep = HiveDecimal.create(step);
        return () -> new AbstractSequentialIterator<HiveDecimal>(HiveDecimal.create(start))
        {
            private int item;

            @Override
            protected HiveDecimal computeNext(HiveDecimal previous)
            {
                if (item >= items) {
                    return null;
                }
                item++;
                return previous.add(hiveStep).setScale(scale);
            }
        };
    }

    private static Function<HiveDecimal, SqlDecimal> toSqlDecimal(int scale)
    {
        return hiveDecimal -> new SqlDecimal(hiveDecimal.unscaledValue(), hiveDecimal.precision(), scale);
    }

    private static ContiguousSet<Integer> intsBetween(int lowerInclusive, int upperExclusive)
    {
        return ContiguousSet.create(Range.openClosed(lowerInclusive, upperExclusive), DiscreteDomain.integers());
    }

    private static Double floatToDouble(Float input)
    {
        if (input == null) {
            return null;
        }
        return input.doubleValue();
    }

    private static Byte intToByte(Integer input)
    {
        if (input == null) {
            return null;
        }
        return input.byteValue();
    }

    private static Short intToShort(Integer input)
    {
        if (input == null) {
            return null;
        }
        return Shorts.checkedCast(input);
    }

    private static Long byteToLong(Byte input)
    {
        return toLong(input);
    }

    private static Long shortToLong(Short input)
    {
        return toLong(input);
    }

    private static Long intToLong(Integer input)
    {
        return toLong(input);
    }

    private static <N extends Number> Long toLong(N input)
    {
        if (input == null) {
            return null;
        }
        return input.longValue();
    }

    private static Timestamp intToTimestamp(Integer input)
    {
        if (input == null) {
            return null;
        }
        Timestamp timestamp = new Timestamp(0);
        long seconds = (input / 1000);
        int nanos = ((input % 1000) * 1_000_000);

        // add some junk nanos to the timestamp, which will be truncated
        nanos += 888_888;

        if (nanos < 0) {
            nanos += 1_000_000_000;
            seconds -= 1;
        }
        if (nanos > 1_000_000_000) {
            nanos -= 1_000_000_000;
            seconds += 1;
        }
        timestamp.setTime(seconds * 1000);
        timestamp.setNanos(nanos);
        return timestamp;
    }

    private static SqlTimestamp intToSqlTimestamp(Integer input)
    {
        if (input == null) {
            return null;
        }
        return new SqlTimestamp(input, UTC_KEY);
    }

    private static Date intToDate(Integer input)
    {
        if (input == null) {
            return null;
        }
        Date date = new Date(0);
        date.setTime(TimeUnit.DAYS.toMillis(input));
        return date;
    }

    private static SqlDate intToSqlDate(Integer input)
    {
        if (input == null) {
            return null;
        }
        return new SqlDate(input);
    }

    private static byte[] stringToByteArray(String input)
    {
        return input.getBytes(UTF_8);
    }

    private static SqlVarbinary byteArrayToVarbinary(byte[] input)
    {
        if (input == null) {
            return null;
        }
        return new SqlVarbinary(input);
    }
}
