/*
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

package org.apache.flink.table.runtime.operators.window.groupwindow.operator;

import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.apache.flink.streaming.util.OneInputStreamOperatorTestHarness;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.runtime.generated.NamespaceAggsHandleFunction;
import org.apache.flink.table.runtime.generated.NamespaceAggsHandleFunctionBase;
import org.apache.flink.table.runtime.generated.NamespaceTableAggsHandleFunction;
import org.apache.flink.table.runtime.generated.RecordEqualiser;
import org.apache.flink.table.runtime.keyselector.RowDataKeySelector;
import org.apache.flink.table.runtime.operators.window.MergeCallback;
import org.apache.flink.table.runtime.operators.window.TimeWindow;
import org.apache.flink.table.runtime.operators.window.Window;
import org.apache.flink.table.runtime.operators.window.groupwindow.assigners.GroupWindowAssigner;
import org.apache.flink.table.runtime.operators.window.groupwindow.assigners.MergingWindowAssigner;
import org.apache.flink.table.runtime.operators.window.groupwindow.triggers.Trigger;
import org.apache.flink.table.types.logical.BigIntType;
import org.apache.flink.table.types.logical.IntType;
import org.apache.flink.table.types.logical.LogicalType;
import org.apache.flink.table.types.logical.VarCharType;
import org.apache.flink.table.utils.HandwrittenSelectorUtil;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import static org.apache.flink.table.runtime.util.StreamRecordUtils.insertRecord;
import static org.apache.flink.table.runtime.util.StreamRecordUtils.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * These tests verify that {@link WindowOperator} correctly interacts with the other windowing
 * components: {@link GroupWindowAssigner}, {@link Trigger}, AggsHandleFunction and window state.
 *
 * <p>These tests document the implicit contract that exists between the windowing components.
 */
class WindowOperatorContractTest {

    private static final ZoneId UTC_ZONE_ID = ZoneId.of("UTC");

    @Test
    void testAssignerIsInvokedOncePerElement() throws Exception {
        GroupWindowAssigner<TimeWindow> mockAssigner = mockTimeWindowAssigner();
        Trigger<TimeWindow> mockTrigger = mockTrigger();
        NamespaceAggsHandleFunction<TimeWindow> mockAggregate = mockAggsHandleFunction();

        OneInputStreamOperatorTestHarness<RowData, RowData> testHarness =
                createWindowOperator(mockAssigner, mockTrigger, mockAggregate, 0L);

        testHarness.open();

        when(mockAssigner.assignWindows(any(), anyLong()))
                .thenReturn(Collections.singletonList(new TimeWindow(0, 0)));

        testHarness.processElement(insertRecord("String", 1, 0L));

        verify(mockAssigner, times(1)).assignWindows(eq(row("String", 1, 0L)), eq(0L));

        testHarness.processElement(insertRecord("String", 1, 0L));

        verify(mockAssigner, times(2)).assignWindows(eq(row("String", 1, 0L)), eq(0L));
    }

    @Test
    void testAssignerWithMultipleWindowsForAggregate() throws Exception {
        GroupWindowAssigner<TimeWindow> mockAssigner = mockTimeWindowAssigner();
        Trigger<TimeWindow> mockTrigger = mockTrigger();
        NamespaceAggsHandleFunction<TimeWindow> mockAggregate = mockAggsHandleFunction();

        OneInputStreamOperatorTestHarness<RowData, RowData> testHarness =
                createWindowOperator(mockAssigner, mockTrigger, mockAggregate, 0L);

        testHarness.open();

        when(mockAssigner.assignWindows(any(), anyLong()))
                .thenReturn(Arrays.asList(new TimeWindow(2, 4), new TimeWindow(0, 2)));

        shouldFireOnElement(mockTrigger);

        testHarness.processElement(insertRecord("String", 1, 0L));

        verify(mockAggregate, times(2)).getValue(anyTimeWindow());
        verify(mockAggregate, times(1)).getValue(eq(new TimeWindow(0, 2)));
        verify(mockAggregate, times(1)).getValue(eq(new TimeWindow(2, 4)));
    }

    @Test
    void testAssignerWithMultipleWindowsForTableAggregate() throws Exception {
        GroupWindowAssigner<TimeWindow> mockAssigner = mockTimeWindowAssigner();
        Trigger<TimeWindow> mockTrigger = mockTrigger();
        NamespaceTableAggsHandleFunction<TimeWindow> mockAggregate = mockTableAggsHandleFunction();

        OneInputStreamOperatorTestHarness<RowData, RowData> testHarness =
                createWindowOperator(mockAssigner, mockTrigger, mockAggregate, 0L);

        testHarness.open();

        when(mockAssigner.assignWindows(any(), anyLong()))
                .thenReturn(Arrays.asList(new TimeWindow(2, 4), new TimeWindow(0, 2)));

        shouldFireOnElement(mockTrigger);

        testHarness.processElement(insertRecord("String", 1, 0L));

        verify(mockAggregate, times(2)).emitValue(anyTimeWindow(), any(), any());
        verify(mockAggregate, times(1)).emitValue(eq(new TimeWindow(0, 2)), any(), any());
        verify(mockAggregate, times(1)).emitValue(eq(new TimeWindow(2, 4)), any(), any());
    }

    @Test
    void testOnElementCalledPerWindow() throws Exception {

        GroupWindowAssigner<TimeWindow> mockAssigner = mockTimeWindowAssigner();
        Trigger<TimeWindow> mockTrigger = mockTrigger();
        NamespaceAggsHandleFunction<TimeWindow> mockAggregate = mockAggsHandleFunction();

        OneInputStreamOperatorTestHarness<RowData, RowData> testHarness =
                createWindowOperator(mockAssigner, mockTrigger, mockAggregate, 0L);

        testHarness.open();

        when(mockAssigner.assignWindows(anyGenericRow(), anyLong()))
                .thenReturn(Arrays.asList(new TimeWindow(2, 4), new TimeWindow(0, 2)));

        testHarness.processElement(insertRecord("String", 42, 1L));

        verify(mockTrigger).onElement(eq(row("String", 42, 1L)), eq(1L), eq(new TimeWindow(2, 4)));
        verify(mockTrigger).onElement(eq(row("String", 42, 1L)), eq(1L), eq(new TimeWindow(0, 2)));
        verify(mockTrigger, times(2)).onElement(any(), anyLong(), anyTimeWindow());
    }

    @Test
    void testMergeWindowsIsCalled() throws Exception {
        MergingWindowAssigner<TimeWindow> mockAssigner = mockMergingAssigner();
        Trigger<TimeWindow> mockTrigger = mockTrigger();
        NamespaceAggsHandleFunction<TimeWindow> mockAggregate = mockAggsHandleFunction();

        OneInputStreamOperatorTestHarness<RowData, RowData> testHarness =
                createWindowOperator(mockAssigner, mockTrigger, mockAggregate, 0L);

        testHarness.open();

        when(mockAssigner.assignWindows(anyGenericRow(), anyLong()))
                .thenReturn(Arrays.asList(new TimeWindow(2, 4), new TimeWindow(0, 2)));

        assertThat(testHarness.getOutput()).isEmpty();

        testHarness.processElement(insertRecord("String", 42, 0L));

        verify(mockAssigner).mergeWindows(eq(new TimeWindow(2, 4)), any(), anyMergeCallback());
        verify(mockAssigner).mergeWindows(eq(new TimeWindow(0, 2)), any(), anyMergeCallback());
        verify(mockAssigner, times(2)).mergeWindows(anyTimeWindow(), any(), anyMergeCallback());
    }

    // ------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private <W extends Window>
            KeyedOneInputStreamOperatorTestHarness<RowData, RowData, RowData> createWindowOperator(
                    GroupWindowAssigner<W> assigner,
                    Trigger<W> trigger,
                    NamespaceAggsHandleFunctionBase<W> aggregationsFunction,
                    long allowedLateness)
                    throws Exception {

        LogicalType[] inputTypes = new LogicalType[] {VarCharType.STRING_TYPE, new IntType()};
        RowDataKeySelector keySelector =
                HandwrittenSelectorUtil.getRowDataSelector(new int[] {0}, inputTypes);
        TypeInformation<RowData> keyType = keySelector.getProducedType();
        LogicalType[] accTypes = new LogicalType[] {new BigIntType(), new BigIntType()};
        LogicalType[] windowTypes = new LogicalType[] {new BigIntType(), new BigIntType()};
        LogicalType[] outputTypeWithoutKeys =
                new LogicalType[] {
                    new BigIntType(), new BigIntType(), new BigIntType(), new BigIntType()
                };

        boolean sendRetraction = allowedLateness > 0;

        if (aggregationsFunction instanceof NamespaceAggsHandleFunction) {
            AggregateWindowOperator operator =
                    new AggregateWindowOperator(
                            (NamespaceAggsHandleFunction) aggregationsFunction,
                            mock(RecordEqualiser.class),
                            assigner,
                            trigger,
                            assigner.getWindowSerializer(new ExecutionConfig()),
                            inputTypes,
                            outputTypeWithoutKeys,
                            accTypes,
                            windowTypes,
                            2,
                            sendRetraction,
                            allowedLateness,
                            UTC_ZONE_ID,
                            -1);
            return new KeyedOneInputStreamOperatorTestHarness<RowData, RowData, RowData>(
                    operator, keySelector, keyType);
        } else {
            TableAggregateWindowOperator operator =
                    new TableAggregateWindowOperator(
                            (NamespaceTableAggsHandleFunction) aggregationsFunction,
                            assigner,
                            trigger,
                            assigner.getWindowSerializer(new ExecutionConfig()),
                            inputTypes,
                            outputTypeWithoutKeys,
                            accTypes,
                            windowTypes,
                            2,
                            sendRetraction,
                            allowedLateness,
                            UTC_ZONE_ID,
                            -1);

            return new KeyedOneInputStreamOperatorTestHarness<RowData, RowData, RowData>(
                    operator, keySelector, keyType);
        }
    }

    private static <W extends Window> NamespaceAggsHandleFunction<W> mockAggsHandleFunction()
            throws Exception {
        return mock(NamespaceAggsHandleFunction.class);
    }

    private static <W extends Window>
            NamespaceTableAggsHandleFunction<W> mockTableAggsHandleFunction() throws Exception {
        NamespaceTableAggsHandleFunction tableAggWindowAggregator =
                mock(NamespaceTableAggsHandleFunction.class);

        when(tableAggWindowAggregator.getAccumulators()).thenReturn(GenericRowData.of());

        return tableAggWindowAggregator;
    }

    private <W extends Window> Trigger<W> mockTrigger() throws Exception {
        @SuppressWarnings("unchecked")
        Trigger<W> mockTrigger = mock(Trigger.class);

        when(mockTrigger.onElement(ArgumentMatchers.any(), anyLong(), ArgumentMatchers.any()))
                .thenReturn(false);
        when(mockTrigger.onEventTime(anyLong(), ArgumentMatchers.any())).thenReturn(false);
        when(mockTrigger.onProcessingTime(anyLong(), ArgumentMatchers.any())).thenReturn(false);

        return mockTrigger;
    }

    private static TimeWindow anyTimeWindow() {
        return Mockito.any();
    }

    private static GenericRowData anyGenericRow() {
        return Mockito.any();
    }

    private static GroupWindowAssigner<TimeWindow> mockTimeWindowAssigner() throws Exception {
        @SuppressWarnings("unchecked")
        GroupWindowAssigner<TimeWindow> mockAssigner = mock(GroupWindowAssigner.class);

        when(mockAssigner.getWindowSerializer(Mockito.any()))
                .thenReturn(new TimeWindow.Serializer());
        when(mockAssigner.isEventTime()).thenReturn(true);

        return mockAssigner;
    }

    private static MergingWindowAssigner<TimeWindow> mockMergingAssigner() throws Exception {
        @SuppressWarnings("unchecked")
        MergingWindowAssigner<TimeWindow> mockAssigner = mock(MergingWindowAssigner.class);

        when(mockAssigner.getWindowSerializer(Mockito.any()))
                .thenReturn(new TimeWindow.Serializer());
        when(mockAssigner.isEventTime()).thenReturn(true);

        return mockAssigner;
    }

    private static MergeCallback<TimeWindow, Collection<TimeWindow>> anyMergeCallback() {
        return Mockito.any();
    }

    // ------------------------------------------------------------------------------------

    private static <T> void shouldFireOnElement(Trigger<TimeWindow> mockTrigger) throws Exception {
        when(mockTrigger.onElement(ArgumentMatchers.any(), anyLong(), anyTimeWindow()))
                .thenReturn(true);
    }
}
