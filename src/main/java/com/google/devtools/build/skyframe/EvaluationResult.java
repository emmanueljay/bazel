// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.skyframe;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.util.Preconditions;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * The result of a Skyframe {@link Evaluator#eval} call. Will contain all the
 * successfully evaluated values, retrievable through {@link #get}. As well, the {@link ErrorInfo}
 * for the first value that failed to evaluate (in the non-keep-going case), or any remaining values
 * that failed to evaluate (in the keep-going case) will be retrievable.
 *
 * <p>A node can never be successfully evaluated and fail to evaluate. Thus, if {@link #get} returns
 * non-null for some key, there is no stored error for that key, and vice versa.
 *
 * @param <T> The type of the values that the caller has requested.
 */
public class EvaluationResult<T extends SkyValue> {

  private final boolean hasError;
  @Nullable private final Exception catastrophe;

  private final Map<SkyKey, T> resultMap;
  private final Map<SkyKey, ErrorInfo> errorMap;
  private final WalkableGraph walkableGraph;

  /**
   * Constructor for the "completed" case. Used only by {@link Builder}.
   */
  private EvaluationResult(
      Map<SkyKey, T> result,
      Map<SkyKey, ErrorInfo> errorMap,
      boolean hasError,
      @Nullable Exception catastrophe,
      @Nullable WalkableGraph walkableGraph) {
    Preconditions.checkState(errorMap.isEmpty() || hasError,
        "result=%s, errorMap=%s", result, errorMap);
    this.resultMap = Preconditions.checkNotNull(result);
    this.errorMap = Preconditions.checkNotNull(errorMap);
    this.hasError = hasError;
    this.catastrophe = catastrophe;
    this.walkableGraph = walkableGraph;
  }

  /**
   * Get a successfully evaluated value.
   */
  public T get(SkyKey key) {
    Preconditions.checkNotNull(resultMap, key);
    return resultMap.get(key);
  }

  /**
   * @return Whether or not the eval successfully evaluated all requested values. Note that this
   * may return true even if all values returned are available in get(). This happens if a top-level
   * value depends transitively on some value that recovered from a {@link SkyFunctionException}.
   */
  public boolean hasError() {
    return hasError;
  }

  /** @return catastrophic error encountered during evaluation, if any */
  @Nullable
  public Exception getCatastrophe() {
    return catastrophe;
  }

  /**
   * @return All successfully evaluated {@link SkyValue}s.
   */
  public Collection<T> values() {
    return Collections.unmodifiableCollection(resultMap.values());
  }

  /**
   * Returns {@link Map} of {@link SkyKey}s to {@link ErrorInfo}. Note that currently some
   * of the returned SkyKeys may not be the ones requested by the user. Moreover, the SkyKey
   * is not necessarily the cause of the error -- it is just the value that was being evaluated
   * when the error was discovered. For the cause of the error, use
   * {@link ErrorInfo#getRootCauses()} on each ErrorInfo.
   */
  public Map<SkyKey, ErrorInfo> errorMap() {
    return ImmutableMap.copyOf(errorMap);
  }

  /**
   * @param key {@link SkyKey} to get {@link ErrorInfo} for.
   */
  public ErrorInfo getError(SkyKey key) {
    return Preconditions.checkNotNull(errorMap, key).get(key);
  }

  /**
   * @return Names of all values that were successfully evaluated. This collection is disjoint from
   *     the keys in {@link #errorMap}.
   */
  public <S> Collection<? extends S> keyNames() {
    return this.<S>getNames(resultMap.keySet());
  }

  @SuppressWarnings("unchecked")
  private <S> Collection<? extends S> getNames(Collection<SkyKey> keys) {
    Collection<S> names = Lists.newArrayListWithCapacity(keys.size());
    for (SkyKey key : keys) {
      names.add((S) key.argument());
    }
    return names;
  }

  @Nullable
  public WalkableGraph getWalkableGraph() {
    return walkableGraph;
  }

  /**
   * Returns some error info. Convenience method equivalent to
   * Iterables.getFirst({@link #errorMap()}, null).getValue().
   */
  public ErrorInfo getError() {
    return Iterables.getFirst(errorMap.entrySet(), null).getValue();
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("hasError", hasError)
        .add("errorMap", errorMap)
        .add("resultMap", resultMap)
        .toString();
  }

  public static <T extends SkyValue> Builder<T> builder() {
    return new Builder<>();
  }

  /**
   * Builder for {@link EvaluationResult}.
   *
   * <p>This is intended only for use in alternative {@code MemoizingEvaluator} implementations.
   */
  public static class Builder<T extends SkyValue> {
    private final Map<SkyKey, T> result = new HashMap<>();
    private final Map<SkyKey, ErrorInfo> errors = new HashMap<>();
    private boolean hasError = false;
    @Nullable private Exception catastrophe = null;
    private WalkableGraph walkableGraph = null;

    /** Adds a value to the result. An error for this key must not already be present. */
    @SuppressWarnings("unchecked")
    public Builder<T> addResult(SkyKey key, SkyValue value) {
      result.put(key, Preconditions.checkNotNull((T) value, key));
      Preconditions.checkState(
          !errors.containsKey(key), "%s in both result and errors: %s %s", value, errors);
      return this;
    }

    /** Adds an error to the result. A successful value for this key must not already be present. */
    public Builder<T> addError(SkyKey key, ErrorInfo error) {
      errors.put(key, Preconditions.checkNotNull(error, key));
      Preconditions.checkState(
          !result.containsKey(key), "%s in both result and errors: %s %s", error, result);
      return this;
    }

    public Builder<T> setWalkableGraph(WalkableGraph walkableGraph) {
      this.walkableGraph = walkableGraph;
      return this;
    }

    public Builder<T> mergeFrom(EvaluationResult<T> otherResult) {
      result.putAll(otherResult.resultMap);
      errors.putAll(otherResult.errorMap);
      hasError |= otherResult.hasError;
      return this;
    }

    public EvaluationResult<T> build() {
      return new EvaluationResult<>(result, errors, hasError, catastrophe, walkableGraph);
    }

    public void setHasError(boolean hasError) {
      this.hasError = hasError;
    }

    public void setCatastrophe(Exception catastrophe) {
      this.catastrophe = catastrophe;
    }
  }
}
