// Copyright (c) 2022, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.conversion;

import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexCode;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.bytecodemetadata.BytecodeMetadataProvider;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.conversion.passes.TrivialGotosCollapser;
import com.android.tools.r8.ir.desugar.nest.D8NestBasedAccessDesugaring;
import com.android.tools.r8.ir.optimize.CodeRewriter;
import com.android.tools.r8.ir.optimize.DeadCodeRemover;
import com.android.tools.r8.ir.optimize.PeepholeOptimizer;
import com.android.tools.r8.ir.optimize.RuntimeWorkaroundCodeRewriter;
import com.android.tools.r8.ir.regalloc.LinearScanRegisterAllocator;
import com.android.tools.r8.ir.regalloc.RegisterAllocator;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.Timing;

public class IRToDexFinalizer extends IRFinalizer<DexCode> {

  private static final int PEEPHOLE_OPTIMIZATION_PASSES = 2;

  private final DeadCodeRemover deadCodeRemover;
  private final InternalOptions options;

  public IRToDexFinalizer(AppView<?> appView, DeadCodeRemover deadCodeRemover) {
    super(appView);
    this.deadCodeRemover = deadCodeRemover;
    this.options = appView.options();
  }

  @Override
  public DexCode finalizeCode(
      IRCode code,
      BytecodeMetadataProvider bytecodeMetadataProvider,
      Timing timing,
      String previousPrintString) {
    if (options.canUseNestBasedAccess()) {
      D8NestBasedAccessDesugaring.checkAndFailOnIncompleteNests(appView);
    }
    DexEncodedMethod method = code.method();
    workaroundBugs(code, timing);
    code.traceBlocks();
    // Perform register allocation.
    RegisterAllocator registerAllocator = performRegisterAllocation(code, method, timing);
    return new DexBuilder(code, bytecodeMetadataProvider, registerAllocator, options).build();
  }

  private void workaroundBugs(IRCode code, Timing timing) {
    RuntimeWorkaroundCodeRewriter.workaroundNumberConversionRegisterAllocationBug(code, options);
    RuntimeWorkaroundCodeRewriter.workaroundDex2OatInliningIssue(appView, code);
    if (RuntimeWorkaroundCodeRewriter.workaroundInstanceOfTypeWeakeningInVerifier(appView, code)) {
      deadCodeRemover.run(code, timing);
    }
    RuntimeWorkaroundCodeRewriter.workaroundSwitchMaxIntBug(code, appView);
    RuntimeWorkaroundCodeRewriter.workaroundDex2OatLinkedListBug(code, options);
    RuntimeWorkaroundCodeRewriter.workaroundForwardingInitializerBug(code, options);
    RuntimeWorkaroundCodeRewriter.workaroundExceptionTargetingLoopHeaderBug(code, options);
    RuntimeWorkaroundCodeRewriter.rewriteJdk8272564Fix(code, options, appView);
    RuntimeWorkaroundCodeRewriter.reportInvokeSuperToInterfaceOnDalvik(code, options, appView);
    assert code.isConsistentSSA(appView);
  }

  @SuppressWarnings("UnusedVariable")
  private RegisterAllocator performRegisterAllocation(
      IRCode code, DexEncodedMethod method, Timing timing) {
    // Always perform dead code elimination before register allocation. The register allocator
    // does not allow dead code (to make sure that we do not waste registers for unneeded values).
    assert deadCodeRemover.verifyNoDeadCode(code);
    timing.begin("Allocate registers");
    LinearScanRegisterAllocator registerAllocator = new LinearScanRegisterAllocator(appView, code);
    registerAllocator.allocateRegisters();
    timing.end();
    TrivialGotosCollapser trivialGotosCollapser = new TrivialGotosCollapser(appView);
    timing.begin("Peephole optimize");
    for (int i = 0; i < PEEPHOLE_OPTIMIZATION_PASSES; i++) {
      trivialGotosCollapser.run(code, timing);
      PeepholeOptimizer.optimize(appView, code, registerAllocator);
    }
    timing.end();
    timing.begin("Clean up");
    CodeRewriter.removeUnneededMovesOnExitingPaths(code, registerAllocator);
    trivialGotosCollapser.run(code, timing);
    timing.end();
    return registerAllocator;
  }
}
