/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.segment.virtual;

import com.google.common.base.Preconditions;
import org.apache.druid.math.expr.Expr;
import org.apache.druid.math.expr.ExprEval;
import org.apache.druid.math.expr.ExprType;
import org.apache.druid.math.expr.ExpressionType;
import org.apache.druid.math.expr.InputBindings;
import org.apache.druid.math.expr.vector.CastToTypeVectorProcessor;
import org.apache.druid.math.expr.vector.ExprVectorProcessor;
import org.apache.druid.math.expr.vector.VectorProcessors;
import org.apache.druid.query.dimension.DefaultDimensionSpec;
import org.apache.druid.query.groupby.DeferExpressionDimensions;
import org.apache.druid.query.groupby.epinephelinae.vector.GroupByVectorColumnSelector;
import org.apache.druid.segment.column.ColumnCapabilities;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.segment.column.RowSignature;
import org.apache.druid.segment.column.Types;
import org.apache.druid.segment.column.ValueType;
import org.apache.druid.segment.vector.ConstantVectorSelectors;
import org.apache.druid.segment.vector.ReadableVectorInspector;
import org.apache.druid.segment.vector.SingleValueDimensionVectorSelector;
import org.apache.druid.segment.vector.VectorColumnSelectorFactory;
import org.apache.druid.segment.vector.VectorObjectSelector;
import org.apache.druid.segment.vector.VectorValueSelector;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public class ExpressionVectorSelectors
{
  private ExpressionVectorSelectors()
  {
    // No instantiation.
  }

  public static SingleValueDimensionVectorSelector makeSingleValueDimensionVectorSelector(
      VectorColumnSelectorFactory factory,
      Expr expression
  )
  {
    final ExpressionPlan plan = ExpressionPlanner.plan(factory, expression);
    Preconditions.checkArgument(plan.is(ExpressionPlan.Trait.VECTORIZABLE));
    // only constant expressions are currently supported, nothing else should get here

    if (plan.isConstant()) {
      String constant = plan.getExpression().eval(InputBindings.nilBindings()).asString();
      return ConstantVectorSelectors.singleValueDimensionVectorSelector(factory.getReadableVectorInspector(), constant);
    }
    if (plan.is(ExpressionPlan.Trait.SINGLE_INPUT_SCALAR) && (plan.getOutputType() != null && plan.getOutputType().is(ExprType.STRING))) {
      return new SingleStringInputDeferredEvaluationExpressionDimensionVectorSelector(
          factory.makeSingleValueDimensionSelector(DefaultDimensionSpec.of(plan.getSingleInputName())),
          plan.getExpression()
      );
    }
    throw new IllegalStateException("Only constant and single input string expressions currently support dictionary encoded selectors");
  }

  public static VectorValueSelector makeVectorValueSelector(
      VectorColumnSelectorFactory factory,
      Expr expression
  )
  {
    final ExpressionPlan plan = ExpressionPlanner.plan(factory, expression);
    Preconditions.checkArgument(plan.is(ExpressionPlan.Trait.VECTORIZABLE));

    if (plan.isConstant()) {
      return ConstantVectorSelectors.vectorValueSelector(
          factory.getReadableVectorInspector(),
          (Number) plan.getExpression().eval(InputBindings.nilBindings()).value()
      );
    }
    final Expr.VectorInputBinding bindings = createVectorBindings(plan.getAnalysis(), factory);
    final ExprVectorProcessor<?> processor = plan.getExpression().asVectorProcessor(bindings);
    return new ExpressionVectorValueSelector(processor, bindings);
  }

  public static VectorObjectSelector makeVectorObjectSelector(
      VectorColumnSelectorFactory factory,
      Expr expression,
      @Nullable ColumnType outputTypeHint
  )
  {
    final ExpressionPlan plan = ExpressionPlanner.plan(factory, expression);
    Preconditions.checkArgument(plan.is(ExpressionPlan.Trait.VECTORIZABLE));

    if (plan.isConstant()) {
      final ExprEval<?> eval = plan.getExpression().eval(InputBindings.nilBindings());
      if (Types.is(outputTypeHint, ValueType.STRING) && eval.type().isArray()) {
        return ConstantVectorSelectors.vectorObjectSelector(
            factory.getReadableVectorInspector(),
            ExpressionSelectors.coerceEvalToObjectOrList(eval)
        );
      }
      return ConstantVectorSelectors.vectorObjectSelector(
          factory.getReadableVectorInspector(),
          eval.value()
      );
    }

    final Expr.VectorInputBinding bindings = createVectorBindings(plan.getAnalysis(), factory);
    final ExprVectorProcessor<?> processor = plan.getExpression().asVectorProcessor(bindings);
    if (Types.is(outputTypeHint, ValueType.STRING) && processor.getOutputType().isArray()) {
      return new ExpressionVectorMultiValueStringObjectSelector(processor, bindings);
    }
    return new ExpressionVectorObjectSelector(processor, bindings);
  }

  /**
   * Creates a {@link ExpressionDeferredGroupByVectorColumnSelector} for the provided expression, if the
   * provided {@link DeferExpressionDimensions} says we should.
   *
   * @param factory                   column selector factory
   * @param expression                expression
   * @param deferExpressionDimensions active value of {@link org.apache.druid.query.groupby.GroupByQueryConfig#CTX_KEY_DEFER_EXPRESSION_DIMENSIONS}
   *
   * @return selector, or null if the {@link DeferExpressionDimensions} determines we should not defer the expression
   */
  @Nullable
  public static GroupByVectorColumnSelector makeGroupByVectorColumnSelector(
      VectorColumnSelectorFactory factory,
      Expr expression,
      DeferExpressionDimensions deferExpressionDimensions
  )
  {
    final ExpressionPlan plan = ExpressionPlanner.plan(factory, expression);
    Preconditions.checkArgument(plan.is(ExpressionPlan.Trait.VECTORIZABLE));

    final List<String> requiredBindings = plan.getAnalysis().getRequiredBindingsList();

    if (!deferExpressionDimensions.useDeferredGroupBySelector(plan, requiredBindings, factory)) {
      return null;
    }

    final RowSignature.Builder requiredBindingsSignatureBuilder = RowSignature.builder();
    final List<GroupByVectorColumnSelector> subSelectors = new ArrayList<>();

    for (final String columnName : requiredBindings) {
      final ColumnCapabilities capabilities = factory.getColumnCapabilities(columnName);
      final ColumnType columnType = capabilities != null ? capabilities.toColumnType() : ColumnType.STRING;
      final GroupByVectorColumnSelector subSelector =
          factory.makeGroupByVectorColumnSelector(columnName, deferExpressionDimensions);
      requiredBindingsSignatureBuilder.add(columnName, columnType);
      subSelectors.add(subSelector);
    }

    return new ExpressionDeferredGroupByVectorColumnSelector(
        expression.asSingleThreaded(factory),
        requiredBindingsSignatureBuilder.build(),
        subSelectors
    );
  }

  public static VectorObjectSelector castValueSelectorToObject(
      ReadableVectorInspector inspector,
      String columnName,
      VectorValueSelector selector,
      ColumnType selectorType,
      ColumnType castTo
  )
  {
    final ExpressionVectorInputBinding binding = new ExpressionVectorInputBinding(inspector);
    binding.addNumeric(columnName, ExpressionType.fromColumnType(selectorType), selector);
    return new ExpressionVectorObjectSelector(
        CastToTypeVectorProcessor.cast(
            VectorProcessors.identifier(binding, columnName),
            ExpressionType.fromColumnType(castTo)
        ),
        binding
    );
  }

  public static VectorObjectSelector castObject(
      ReadableVectorInspector inspector,
      String columnName,
      VectorObjectSelector selector,
      ColumnType selectorType,
      ColumnType castTo
  )
  {
    final ExpressionVectorInputBinding binding = new ExpressionVectorInputBinding(inspector);
    binding.addObjectSelector(columnName, ExpressionType.fromColumnType(selectorType), selector);
    return new ExpressionVectorObjectSelector(
        CastToTypeVectorProcessor.cast(
            VectorProcessors.identifier(binding, columnName),
            ExpressionType.fromColumnType(castTo)
        ),
        binding
    );
  }

  public static VectorValueSelector castObjectSelectorToNumeric(
      ReadableVectorInspector inspector,
      String columnName,
      VectorObjectSelector selector,
      ColumnType selectorType,
      ColumnType castTo
  )
  {
    Preconditions.checkArgument(castTo.isNumeric(), "Must cast to a numeric type to make a value selector");
    ExpressionVectorInputBinding binding = new ExpressionVectorInputBinding(inspector);
    binding.addObjectSelector(columnName, ExpressionType.fromColumnType(selectorType), selector);
    return new ExpressionVectorValueSelector(
        CastToTypeVectorProcessor.cast(
            VectorProcessors.identifier(binding, columnName),
            ExpressionType.fromColumnType(castTo)
        ),
        binding
    );
  }

  private static Expr.VectorInputBinding createVectorBindings(
      Expr.BindingAnalysis bindingAnalysis,
      VectorColumnSelectorFactory vectorColumnSelectorFactory
  )
  {
    ExpressionVectorInputBinding binding = new ExpressionVectorInputBinding(
        vectorColumnSelectorFactory.getReadableVectorInspector()
    );
    final List<String> columns = bindingAnalysis.getRequiredBindingsList();
    for (String columnName : columns) {
      final ColumnCapabilities columnCapabilities = vectorColumnSelectorFactory.getColumnCapabilities(columnName);

      // null capabilities should be backed by a nil vector selector since it means the column effectively doesnt exist
      if (columnCapabilities != null) {
        switch (columnCapabilities.getType()) {
          case FLOAT:
          case DOUBLE:
            binding.addNumeric(columnName, ExpressionType.DOUBLE, vectorColumnSelectorFactory.makeValueSelector(columnName));
            break;
          case LONG:
            binding.addNumeric(columnName, ExpressionType.LONG, vectorColumnSelectorFactory.makeValueSelector(columnName));
            break;
          default:
            binding.addObjectSelector(
                columnName,
                ExpressionType.fromColumnType(columnCapabilities.toColumnType()),
                vectorColumnSelectorFactory.makeObjectSelector(columnName)
            );
        }
      }
    }
    return binding;
  }
}
