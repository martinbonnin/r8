// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.conversion.callgraph;

import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.utils.collections.ProgramMethodSet;
import com.google.common.collect.Iterators;
import com.google.common.collect.Sets;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

public class CycleEliminator<N extends CycleEliminatorNode<N>> {

  public static final String CYCLIC_FORCE_INLINING_MESSAGE =
      "Unable to satisfy force inlining constraints due to cyclic force inlining";

  private static class CallEdge<N extends CycleEliminatorNode<N>> {

    private final N caller;
    private final N callee;

    CallEdge(N caller, N callee) {
      this.caller = caller;
      this.callee = callee;
    }
  }

  static class StackEntryInfo<N extends CycleEliminatorNode<N>> {

    final int index;
    final N predecessor;

    boolean processed;

    StackEntryInfo(int index, N predecessor) {
      this.index = index;
      this.predecessor = predecessor;
    }
  }

  public static class CycleEliminationResult {

    private Map<DexEncodedMethod, ProgramMethodSet> removedCallEdges;

    CycleEliminationResult(Map<DexEncodedMethod, ProgramMethodSet> removedCallEdges) {
      this.removedCallEdges = removedCallEdges;
    }

    public int numberOfRemovedCallEdges() {
      int numberOfRemovedCallEdges = 0;
      for (ProgramMethodSet nodes : removedCallEdges.values()) {
        numberOfRemovedCallEdges += nodes.size();
      }
      return numberOfRemovedCallEdges;
    }
  }

  // DFS stack.
  private Deque<N> stack = new ArrayDeque<>();

  // Nodes on the DFS stack.
  private Map<N, StackEntryInfo<N>> stackEntryInfo = new IdentityHashMap<>();

  // Subset of the DFS stack, where the nodes on the stack are class initializers.
  //
  // This stack is used to efficiently compute if there is a class initializer on the stack.
  private Deque<N> clinitCallStack = new ArrayDeque<>();

  // Subset of the DFS stack, where the nodes on the stack satisfy that the edge from the
  // predecessor to the node itself is a field read edge.
  //
  // This stack is used to efficiently compute if there is a field read edge inside a cycle when
  // a cycle is found.
  private Deque<N> writerStack = new ArrayDeque<>();

  // Set of nodes that have been visited entirely.
  private Set<N> marked = Sets.newIdentityHashSet();

  // Call edges that should be removed when the caller has been processed. These are not removed
  // directly since that would lead to ConcurrentModificationExceptions.
  private Map<N, Set<N>> calleesToBeRemoved = new IdentityHashMap<>();

  // Field read edges that should be removed when the reader has been processed. These are not
  // removed directly since that would lead to ConcurrentModificationExceptions.
  private Map<N, Set<N>> writersToBeRemoved = new IdentityHashMap<>();

  // Mapping from callee to the set of callers that were removed from the callee.
  private Map<DexEncodedMethod, ProgramMethodSet> removedCallEdges = new IdentityHashMap<>();

  // Set of nodes from which cycle elimination must be rerun to ensure that all cycles will be
  // removed.
  private LinkedHashSet<N> revisit = new LinkedHashSet<>();

  public CycleEliminationResult breakCycles(Collection<N> roots) {
    // Break cycles in this call graph by removing edges causing cycles. We do this in a fixpoint
    // because the algorithm does not guarantee that all cycles will be removed from the graph
    // when we remove an edge in the middle of a cycle that contains another cycle.
    do {
      traverse(roots);
      roots = revisit;
      prepareForNewTraversal();
    } while (!roots.isEmpty());

    CycleEliminationResult result = new CycleEliminationResult(removedCallEdges);
    reset();
    return result;
  }

  private void prepareForNewTraversal() {
    assert calleesToBeRemoved.isEmpty();
    assert clinitCallStack.isEmpty();
    assert stack.isEmpty();
    assert stackEntryInfo.isEmpty();
    assert writersToBeRemoved.isEmpty();
    assert writerStack.isEmpty();
    marked.clear();
    revisit = new LinkedHashSet<>();
  }

  private void reset() {
    assert clinitCallStack.isEmpty();
    assert marked.isEmpty();
    assert revisit.isEmpty();
    assert stack.isEmpty();
    assert stackEntryInfo.isEmpty();
    assert writerStack.isEmpty();
    removedCallEdges = new IdentityHashMap<>();
  }

  private static class WorkItem<N extends CycleEliminatorNode<N>> {
    boolean isNode() {
      return false;
    }

    NodeWorkItem<N> asNode() {
      return null;
    }

    boolean isIterator() {
      return false;
    }

    IteratorWorkItem<N> asIterator() {
      return null;
    }
  }

  private static class NodeWorkItem<N extends CycleEliminatorNode<N>> extends WorkItem<N> {
    private final N node;

    NodeWorkItem(N node) {
      this.node = node;
    }

    @Override
    boolean isNode() {
      return true;
    }

    @Override
    NodeWorkItem<N> asNode() {
      return this;
    }
  }

  private static class IteratorWorkItem<N extends CycleEliminatorNode<N>> extends WorkItem<N> {
    private final N callerOrReader;
    private final Iterator<N> calleesAndWriters;

    IteratorWorkItem(N callerOrReader, Iterator<N> calleesAndWriters) {
      this.callerOrReader = callerOrReader;
      this.calleesAndWriters = calleesAndWriters;
    }

    @Override
    boolean isIterator() {
      return true;
    }

    @Override
    IteratorWorkItem<N> asIterator() {
      return this;
    }
  }

  private void traverse(Collection<N> roots) {
    Deque<WorkItem<N>> workItems = new ArrayDeque<>(roots.size());
    for (N node : roots) {
      workItems.addLast(new NodeWorkItem<>(node));
    }
    while (!workItems.isEmpty()) {
      WorkItem<N> workItem = workItems.removeFirst();
      if (workItem.isNode()) {
        N node = workItem.asNode().node;
        if (marked.contains(node)) {
          // Already visited all nodes that can be reached from this node.
          continue;
        }

        N predecessor = stack.isEmpty() ? null : stack.peek();
        push(node, predecessor);

        // The callees and writers must be sorted before calling traverse recursively.
        // This ensures that cycles are broken the same way across multiple compilations.
        Iterator<N> calleesAndWriterIterator =
            Iterators.concat(
                node.getCalleesWithDeterministicOrder().iterator(),
                node.getWritersWithDeterministicOrder().iterator());
        workItems.addFirst(new IteratorWorkItem<>(node, calleesAndWriterIterator));
      } else {
        assert workItem.isIterator();
        IteratorWorkItem<N> iteratorWorkItem = workItem.asIterator();
        N newCallerOrReader =
            iterateCalleesAndWriters(
                iteratorWorkItem.calleesAndWriters, iteratorWorkItem.callerOrReader);
        if (newCallerOrReader != null) {
          // We did not finish the work on this iterator, so add it again.
          workItems.addFirst(iteratorWorkItem);
          workItems.addFirst(new NodeWorkItem<>(newCallerOrReader));
        } else {
          assert !iteratorWorkItem.calleesAndWriters.hasNext();
          pop(iteratorWorkItem.callerOrReader);
          marked.add(iteratorWorkItem.callerOrReader);

          Collection<N> calleesToBeRemovedFromCaller =
              calleesToBeRemoved.remove(iteratorWorkItem.callerOrReader);
          if (calleesToBeRemovedFromCaller != null) {
            calleesToBeRemovedFromCaller.forEach(
                callee -> {
                  callee.removeCaller(iteratorWorkItem.callerOrReader);
                  recordCallEdgeRemoval(iteratorWorkItem.callerOrReader, callee);
                });
          }

          Collection<N> writersToBeRemovedFromReader =
              writersToBeRemoved.remove(iteratorWorkItem.callerOrReader);
          if (writersToBeRemovedFromReader != null) {
            writersToBeRemovedFromReader.forEach(
                writer -> writer.removeReader(iteratorWorkItem.callerOrReader));
          }
        }
      }
    }
  }

  private N iterateCalleesAndWriters(Iterator<N> calleeOrWriterIterator, N callerOrReader) {
    while (calleeOrWriterIterator.hasNext()) {
      N calleeOrWriter = calleeOrWriterIterator.next();
      StackEntryInfo<N> calleeOrWriterStackEntryInfo = stackEntryInfo.get(calleeOrWriter);
      boolean foundCycle = calleeOrWriterStackEntryInfo != null;
      if (!foundCycle) {
        return calleeOrWriter;
      }

      // Found a cycle that needs to be eliminated. If it is a field read edge, then remove it
      // right away.
      boolean isFieldReadEdge = calleeOrWriter.hasReader(callerOrReader);
      if (isFieldReadEdge) {
        removeFieldReadEdge(callerOrReader, calleeOrWriter);
        continue;
      }

      // Otherwise, it is a call edge. Check if there is a field read edge in the cycle, and if
      // so, remove that edge.
      if (!writerStack.isEmpty()
          && removeIncomingEdgeOnStack(
              writerStack.peek(),
              calleeOrWriter,
              calleeOrWriterStackEntryInfo,
              this::removeFieldReadEdge)) {
        continue;
      }

      // It is a call edge and the cycle does not contain any field read edges.
      // If it is a call edge to a <clinit>, then remove it.
      if (calleeOrWriter.getMethod().isClassInitializer()) {
        // Calls to class initializers are always safe to remove.
        assert callEdgeRemovalIsSafe(callerOrReader, calleeOrWriter);
        removeCallEdge(callerOrReader, calleeOrWriter);
        continue;
      }

      // Otherwise, check if there is a call edge to a <clinit> method in the cycle, and if so,
      // remove that edge.
      if (!clinitCallStack.isEmpty()
          && removeIncomingEdgeOnStack(
              clinitCallStack.peek(),
              calleeOrWriter,
              calleeOrWriterStackEntryInfo,
              this::removeCallEdge)) {
        continue;
      }

      // Otherwise, we remove the call edge if it is safe according to force inlining.
      if (callEdgeRemovalIsSafe(callerOrReader, calleeOrWriter)) {
        // Break the cycle by removing the edge node->calleeOrWriter.
        // Need to remove `calleeOrWriter` from `node.callees` using the iterator to prevent a
        // ConcurrentModificationException.
        removeCallEdge(callerOrReader, calleeOrWriter);
        continue;
      }

      // The call edge cannot be removed due to force inlining. Find another call edge in the
      // cycle that can safely be removed instead.
      LinkedList<N> cycle = extractCycle(calleeOrWriter);

      // Break the cycle by finding an edge that can be removed without breaking force
      // inlining. If that is not possible, this call fails with a compilation error.
      CallEdge<N> edge = findCallEdgeForRemoval(cycle);

      // The edge will be null if this cycle has already been eliminated as a result of
      // another cycle elimination.
      if (edge != null) {
        assert callEdgeRemovalIsSafe(edge.caller, edge.callee);

        // Break the cycle by removing the edge caller->callee.
        removeCallEdge(edge.caller, edge.callee);
        revisit.add(edge.callee);
      }

      // Recover the stack.
      recoverStack(cycle);
    }
    return null;
  }

  private void push(N node, N predecessor) {
    stack.push(node);
    assert !stackEntryInfo.containsKey(node);
    stackEntryInfo.put(node, new StackEntryInfo<>(stack.size() - 1, predecessor));
    if (predecessor != null) {
      if (node.getMethod().isClassInitializer() && node.hasCaller(predecessor)) {
        clinitCallStack.push(node);
      } else if (predecessor.getWritersWithDeterministicOrder().contains(node)) {
        writerStack.push(node);
      }
    }
  }

  private void pop(N node) {
    N popped = stack.pop();
    assert popped == node;
    assert stackEntryInfo.containsKey(node);
    stackEntryInfo.remove(node);
    if (clinitCallStack.peek() == popped) {
      assert writerStack.peek() != popped;
      clinitCallStack.pop();
    } else if (writerStack.peek() == popped) {
      writerStack.pop();
    }
  }

  private void removeCallEdge(N caller, N callee) {
    calleesToBeRemoved.computeIfAbsent(caller, ignore -> Sets.newIdentityHashSet()).add(callee);
  }

  private void removeFieldReadEdge(N reader, N writer) {
    writersToBeRemoved.computeIfAbsent(reader, ignore -> Sets.newIdentityHashSet()).add(writer);
  }

  private boolean removeIncomingEdgeOnStack(
      N target,
      N currentCalleeOrWriter,
      StackEntryInfo<N> currentCalleeOrWriterStackEntryInfo,
      BiConsumer<N, N> edgeRemover) {
    StackEntryInfo<N> targetStackEntryInfo = stackEntryInfo.get(target);
    boolean cycleContainsTarget =
        targetStackEntryInfo.index > currentCalleeOrWriterStackEntryInfo.index;
    if (cycleContainsTarget) {
      assert verifyCycleSatisfies(
          currentCalleeOrWriter,
          cycle -> cycle.contains(target) && cycle.contains(targetStackEntryInfo.predecessor));
      if (!targetStackEntryInfo.processed) {
        edgeRemover.accept(targetStackEntryInfo.predecessor, target);
        revisit.add(target);
        targetStackEntryInfo.processed = true;
      }
      return true;
    }
    return false;
  }

  // TODO(b/270398965): Replace LinkedList.
  @SuppressWarnings("JdkObsolete")
  private LinkedList<N> extractCycle(N entry) {
    LinkedList<N> cycle = new LinkedList<>();
    do {
      assert !stack.isEmpty();
      cycle.add(stack.pop());
    } while (cycle.getLast() != entry);
    return cycle;
  }

  private boolean verifyCycleSatisfies(N entry, Predicate<LinkedList<N>> predicate) {
    LinkedList<N> cycle = extractCycle(entry);
    assert predicate.test(cycle);
    recoverStack(cycle);
    return true;
  }

  private CallEdge<N> findCallEdgeForRemoval(LinkedList<N> extractedCycle) {
    N callee = extractedCycle.getLast();
    for (N caller : extractedCycle) {
      if (caller.hasWriter(callee)) {
        // Not a call edge.
        assert !caller.hasCallee(callee);
        assert !callee.hasCaller(caller);
        callee = caller;
        continue;
      }
      if (!caller.hasCallee(callee)) {
        // No need to break any edges since this cycle has already been broken previously.
        assert !callee.hasCaller(caller);
        return null;
      }
      if (callEdgeRemovalIsSafe(caller, callee)) {
        return new CallEdge<N>(caller, callee);
      }
      callee = caller;
    }
    throw new CompilationError(CYCLIC_FORCE_INLINING_MESSAGE);
  }

  private static <N extends CycleEliminatorNode<N>> boolean callEdgeRemovalIsSafe(
      N callerOrReader, N calleeOrWriter) {
    // All call edges where the callee is a method that should be force inlined must be kept,
    // to guarantee that the IR converter will process the callee before the caller.
    assert calleeOrWriter.hasCaller(callerOrReader);
    return !calleeOrWriter.getMethod().getOptimizationInfo().forceInline();
  }

  private void recordCallEdgeRemoval(N caller, N callee) {
    removedCallEdges
        .computeIfAbsent(callee.getMethod(), ignore -> ProgramMethodSet.create(2))
        .add(caller.getProgramMethod());
  }

  private void recoverStack(LinkedList<N> extractedCycle) {
    Iterator<N> descendingIt = extractedCycle.descendingIterator();
    while (descendingIt.hasNext()) {
      stack.push(descendingIt.next());
    }
  }
}
