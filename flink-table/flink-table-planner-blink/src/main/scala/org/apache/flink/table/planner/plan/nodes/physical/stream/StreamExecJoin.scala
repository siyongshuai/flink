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

package org.apache.flink.table.planner.plan.nodes.physical.stream

import org.apache.flink.api.dag.Transformation
import org.apache.flink.streaming.api.transformations.TwoInputTransformation
import org.apache.flink.table.data.RowData
import org.apache.flink.table.planner.calcite.FlinkTypeFactory
import org.apache.flink.table.planner.delegation.StreamPlanner
import org.apache.flink.table.planner.plan.nodes.common.CommonPhysicalJoin
import org.apache.flink.table.planner.plan.nodes.exec.{ExecNode, StreamExecNode}
import org.apache.flink.table.planner.plan.utils.{JoinUtil, KeySelectorUtil}
import org.apache.flink.table.runtime.operators.join.stream.state.JoinInputSideSpec
import org.apache.flink.table.runtime.operators.join.stream.{StreamingJoinOperator, StreamingSemiAntiJoinOperator}
import org.apache.flink.table.runtime.typeutils.InternalTypeInfo

import org.apache.calcite.plan._
import org.apache.calcite.rel.core.{Join, JoinRelType}
import org.apache.calcite.rel.metadata.RelMetadataQuery
import org.apache.calcite.rel.{RelNode, RelWriter}
import org.apache.calcite.rex.RexNode

import java.util

import scala.collection.JavaConversions._

/**
  * Stream physical RelNode for regular [[Join]].
  *
  * Regular joins are the most generic type of join in which any new records or changes to
  * either side of the join input are visible and are affecting the whole join result.
  */
class StreamExecJoin(
    cluster: RelOptCluster,
    traitSet: RelTraitSet,
    leftRel: RelNode,
    rightRel: RelNode,
    condition: RexNode,
    joinType: JoinRelType)
  extends CommonPhysicalJoin(cluster, traitSet, leftRel, rightRel, condition, joinType)
  with StreamPhysicalRel
  with StreamExecNode[RowData] {

  /**
   * This is mainly used in `FlinkChangelogModeInferenceProgram.SatisfyUpdateKindTraitVisitor`.
   * If the unique key of input contains join key, then it can support ignoring UPDATE_BEFORE.
   * Otherwise, it can't ignore UPDATE_BEFORE. For example, if the input schema is [id, name, cnt]
   * with the unique key (id). The join key is (id, name), then an insert and update on the id:
   *
   * +I(1001, Tim, 10)
   * -U(1001, Tim, 10)
   * +U(1001, Timo, 11)
   *
   * If the UPDATE_BEFORE is ignored, the `+I(1001, Tim, 10)` record in join will never be
   * retracted. Therefore, if we want to ignore UPDATE_BEFORE, the unique key must contain
   * join key.
   *
   * @see FlinkChangelogModeInferenceProgram
   */
  def inputUniqueKeyContainsJoinKey(inputOrdinal: Int): Boolean = {
    val input = getInput(inputOrdinal)
    val inputUniqueKeys = getCluster.getMetadataQuery.getUniqueKeys(input)
    if (inputUniqueKeys != null) {
      val joinKeys = if (inputOrdinal == 0) {
        // left input
        keyPairs.map(_.source).toArray
      } else {
        // right input
        keyPairs.map(_.target).toArray
      }
      inputUniqueKeys.exists {
        uniqueKey => joinKeys.forall(uniqueKey.toArray.contains(_))
      }
    } else {
      false
    }
  }

  override def requireWatermark: Boolean = false

  override def copy(
      traitSet: RelTraitSet,
      conditionExpr: RexNode,
      left: RelNode,
      right: RelNode,
      joinType: JoinRelType,
      semiJoinDone: Boolean): Join = {
    new StreamExecJoin(cluster, traitSet, left, right, conditionExpr, joinType)
  }

  override def explainTerms(pw: RelWriter): RelWriter = {
    super
      .explainTerms(pw)
      .item("leftInputSpec", analyzeJoinInput(left))
      .item("rightInputSpec", analyzeJoinInput(right))
  }

  override def computeSelfCost(planner: RelOptPlanner, metadata: RelMetadataQuery): RelOptCost = {
    val elementRate = 100.0d * 2 // two input stream
    planner.getCostFactory.makeCost(elementRate, elementRate, 0)
  }

  //~ ExecNode methods -----------------------------------------------------------

  override def getInputNodes: util.List[ExecNode[StreamPlanner, _]] = {
    getInputs.map(_.asInstanceOf[ExecNode[StreamPlanner, _]])
  }

  override def replaceInputNode(
      ordinalInParent: Int,
      newInputNode: ExecNode[StreamPlanner, _]): Unit = {
    replaceInput(ordinalInParent, newInputNode.asInstanceOf[RelNode])
  }

  override protected def translateToPlanInternal(
      planner: StreamPlanner): Transformation[RowData] = {

    val tableConfig = planner.getTableConfig
    val returnType = InternalTypeInfo.of(FlinkTypeFactory.toLogicalRowType(getRowType))

    val leftTransform = getInputNodes.get(0).translateToPlan(planner)
      .asInstanceOf[Transformation[RowData]]
    val rightTransform = getInputNodes.get(1).translateToPlan(planner)
      .asInstanceOf[Transformation[RowData]]

    val leftType = leftTransform.getOutputType.asInstanceOf[InternalTypeInfo[RowData]]
    val rightType = rightTransform.getOutputType.asInstanceOf[InternalTypeInfo[RowData]]

    val (leftJoinKey, rightJoinKey) =
      JoinUtil.checkAndGetJoinKeys(keyPairs, getLeft, getRight, allowEmptyKey = true)

    val leftSelect = KeySelectorUtil.getRowDataSelector(leftJoinKey, leftType)
    val rightSelect = KeySelectorUtil.getRowDataSelector(rightJoinKey, rightType)

    val leftInputSpec = analyzeJoinInput(left)
    val rightInputSpec = analyzeJoinInput(right)

    val generatedCondition = JoinUtil.generateConditionFunction(
      tableConfig,
      cluster.getRexBuilder,
      getJoinInfo,
      leftType.toRowType,
      rightType.toRowType)

    val minRetentionTime = tableConfig.getMinIdleStateRetentionTime

    val operator = if (joinType == JoinRelType.ANTI || joinType == JoinRelType.SEMI) {
      new StreamingSemiAntiJoinOperator(
        joinType == JoinRelType.ANTI,
        leftType,
        rightType,
        generatedCondition,
        leftInputSpec,
        rightInputSpec,
        filterNulls,
        minRetentionTime)
    } else {
      val leftIsOuter = joinType == JoinRelType.LEFT || joinType == JoinRelType.FULL
      val rightIsOuter = joinType == JoinRelType.RIGHT || joinType == JoinRelType.FULL
      new StreamingJoinOperator(
        leftType,
        rightType,
        generatedCondition,
        leftInputSpec,
        rightInputSpec,
        leftIsOuter,
        rightIsOuter,
        filterNulls,
        minRetentionTime)
    }

    val ret = new TwoInputTransformation[RowData, RowData, RowData](
      leftTransform,
      rightTransform,
      getRelDetailedDescription,
      operator,
      returnType,
      leftTransform.getParallelism)

    if (inputsContainSingleton()) {
      ret.setParallelism(1)
      ret.setMaxParallelism(1)
    }

    // set KeyType and Selector for state
    ret.setStateKeySelectors(leftSelect, rightSelect)
    ret.setStateKeyType(leftSelect.getProducedType)
    ret
  }

  private def analyzeJoinInput(input: RelNode): JoinInputSideSpec = {
    val uniqueKeys = cluster.getMetadataQuery.getUniqueKeys(input)
    if (uniqueKeys == null || uniqueKeys.isEmpty) {
      JoinInputSideSpec.withoutUniqueKey()
    } else {
      val inRowType = InternalTypeInfo.of(FlinkTypeFactory.toLogicalRowType(input.getRowType))
      val joinKeys = if (input == left) {
        keyPairs.map(_.source).toArray
      } else {
        keyPairs.map(_.target).toArray
      }
      val uniqueKeysContainedByJoinKey = uniqueKeys
        .filter(uk => uk.toArray.forall(joinKeys.contains(_)))
        .map(_.toArray)
        .toArray
      if (uniqueKeysContainedByJoinKey.nonEmpty) {
        // join key contains unique key
        val smallestUniqueKey = getSmallestKey(uniqueKeysContainedByJoinKey)
        val uniqueKeySelector = KeySelectorUtil.getRowDataSelector(smallestUniqueKey, inRowType)
        val uniqueKeyTypeInfo = uniqueKeySelector.getProducedType
        JoinInputSideSpec.withUniqueKeyContainedByJoinKey(uniqueKeyTypeInfo, uniqueKeySelector)
      } else {
        val smallestUniqueKey = getSmallestKey(uniqueKeys.map(_.toArray).toArray)
        val uniqueKeySelector = KeySelectorUtil.getRowDataSelector(smallestUniqueKey, inRowType)
        val uniqueKeyTypeInfo = uniqueKeySelector.getProducedType
        JoinInputSideSpec.withUniqueKey(uniqueKeyTypeInfo, uniqueKeySelector)
      }
    }
  }

  private def getSmallestKey(keys: Array[Array[Int]]): Array[Int] = {
    var smallest = keys.head
    for (key <- keys) {
      if (key.length < smallest.length) {
        smallest = key
      }
    }
    smallest
  }
}
