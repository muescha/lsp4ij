/*******************************************************************************
 * Copyright (c) 2025 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 * Red Hat, Inc. - initial API and implementation
 ******************************************************************************/
package com.redhat.devtools.lsp4ij.dap.breakpoints;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XExpression;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.breakpoints.XBreakpoint;
import com.intellij.xdebugger.breakpoints.XBreakpointType;
import com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptor;
import org.eclipse.lsp4j.debug.*;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static com.redhat.devtools.lsp4ij.dap.DAPIJUtils.*;

/**
 * Abstract Debug Adapter Protocol (DAP) breakpoint handler.
 */
public abstract class DAPBreakpointHandlerBase<B extends XBreakpoint<?>> extends BreakpointHandlerBase<B> {

    private static final SourceBreakpoint[] EMPTY_SOURCE_BREAKPOINTS = new SourceBreakpoint[0];
    private final @NotNull DAPExceptionBreakpointsPanel exceptionBreakpointsPanel;

    public DAPBreakpointHandlerBase(@NotNull Class<? extends XBreakpointType<B, ?>> breakpointTypeClass,
                                    @NotNull XDebugSession debugSession,
                                    @NotNull DebugAdapterDescriptor debugAdapterDescriptor,
                                    @NotNull Project project) {
        super(breakpointTypeClass, debugSession, debugAdapterDescriptor, project);
        this.exceptionBreakpointsPanel = new DAPExceptionBreakpointsPanel(this);
    }

    /**
     * Returns whether this target can install the given breakpoint.
     *
     * @param breakpoint breakpoint to consider
     * @return whether this target can install the given breakpoint
     */
    @Override
    public boolean supportsBreakpoint(B breakpoint) {
        if (!supportsBreakpointType(breakpoint)) {
            return false;
        }

        XSourcePosition sourcePosition = breakpoint.getSourcePosition();
        if (sourcePosition == null) {
            return false;
        }
        return debugAdapterDescriptor.isDebuggableFile(sourcePosition.getFile(), project);
    }

    private static @NotNull SetBreakpointsArguments createSetBreakpointsArguments(@NotNull Source source,
                                                                                  @NotNull List<SourceBreakpoint> sourceBreakpoints) {
        int[] lines = sourceBreakpoints
                .stream()
                .mapToInt(SourceBreakpoint::getLine)
                .toArray();

        final var arguments = new SetBreakpointsArguments();
        arguments.setSource(source);
        arguments.setLines(lines);
        arguments.setBreakpoints(sourceBreakpoints.toArray(EMPTY_SOURCE_BREAKPOINTS));
        arguments.setSourceModified(false);
        return arguments;
    }

    private static <T> void addSourceBreakpoint(@NotNull XSourcePosition sourcePosition,
                                                @Nullable T ijBreakpoint,
                                                @Nullable XExpression conditionExpression,
                                                @NotNull Map<Source, List<SourceBreakpoint>> targetBreakpoints,
                                                @NotNull Map<Source, List<@Nullable T>> sentBreakpoints) {
        Source source = toDAPSource(sourcePosition);
        int lineNumber = getLineNumber(sourcePosition);
        SourceBreakpoint sourceBreakpoint = new SourceBreakpoint();
        sourceBreakpoint.setLine(lineNumber);
        if (conditionExpression != null) {
            // Breakpoint with condition
            sourceBreakpoint.setCondition(conditionExpression.getExpression());
        }
        List<SourceBreakpoint> sourceBreakpoints = targetBreakpoints
                .computeIfAbsent(source, s -> new ArrayList<>());
        sourceBreakpoints.add(sourceBreakpoint);
        // Keep the IntelliJ breakpoint at the same index as its DAP source breakpoint
        // (null for a temporary breakpoint), to map the 'setBreakpoints' response.
        sentBreakpoints
                .computeIfAbsent(source, s -> new ArrayList<>())
                .add(ijBreakpoint);
    }

    private static @NotNull Source toDAPSource(@NotNull XSourcePosition sourcePosition) {
        Source source = new Source();
        source.setPath(getFilePath(sourcePosition.getFile()));
        source.setName(getFileName(sourcePosition.getFile()));
        return source;
    }

    private static int getLineNumber(@NotNull XSourcePosition sourcePosition) {
        return sourcePosition.getLine() + 1;
    }

    @Override
    protected @NotNull CompletableFuture<@Nullable Void> doSendBreakpoints(@Nullable IDebugProtocolServer debugProtocolServer,
                                                                           @Nullable TemporaryBreakpoint temporaryBreakpoint) {
        // Convert list of IJ XBreakpoint -> LSP SourceBreakpoint
        Map<Source, List<SourceBreakpoint>> targetBreakpoints = new HashMap<>();
        Map<Source, List<@Nullable B>> sentBreakpoints = new HashMap<>();
        for (B breakpoint : breakpoints) {
            var sourcePosition = breakpoint.getSourcePosition();
            if (sourcePosition != null) {
                addSourceBreakpoint(sourcePosition, breakpoint, breakpoint.getConditionExpression(), targetBreakpoints, sentBreakpoints);
            }
        }

        if (temporaryBreakpoint != null) {
            if (temporaryBreakpoint.add()) {
                addSourceBreakpoint(temporaryBreakpoint.sourcePosition(), null, null, targetBreakpoints, sentBreakpoints);
            } else {
                // Removed IntelliJ breakpoint
                // Create a Source with empty source breakpoints if it doesn't exist,
                // in order to disable removed breakpoint
                // if the source contained only this breakpoint.
                Source source = toDAPSource(temporaryBreakpoint.sourcePosition());
                targetBreakpoints
                        .computeIfAbsent(source, s -> new ArrayList<>());
            }
        }

        final var setBreakpointsFutures = new ArrayList<CompletableFuture<Void>>();
        for (Iterator<Map.Entry<Source, List<SourceBreakpoint>>> iterator = targetBreakpoints.entrySet()
                .iterator(); iterator.hasNext(); ) {
            Map.Entry<Source, List<SourceBreakpoint>> entry = iterator.next();

            Source source = entry.getKey();
            List<SourceBreakpoint> sourceBreakpoints = entry.getValue();

            // Call 'setBreakpoints' DAP request for each Source keys.
            final var arguments = createSetBreakpointsArguments(source, sourceBreakpoints);
            final List<@Nullable B> sent = sentBreakpoints.getOrDefault(source, List.of());
            if (debugProtocolServer != null) {
                // Call 'setBreakpoints' DAP request for each Source keys
                // and the given debug protocol server
                setBreakpointsFutures.add(setBreakpoints(debugProtocolServer, arguments, sent));
            } else {
                // Call 'setBreakpoints' DAP request for each Source keys
                // for each registered debug protocol server
                for (var server : debugProtocolServers) {
                    setBreakpointsFutures.add(setBreakpoints(server, arguments, sent));
                }
            }

            // Once we told adapter there are no breakpoints for a source file, we can stop
            // tracking that file
            if (sourceBreakpoints.isEmpty()) {
                iterator.remove();
            }
        }
        return CompletableFuture.allOf(setBreakpointsFutures.toArray(EMPTY_COMPLETABLE_FUTURES));
    }

    /**
     * Sends the given 'setBreakpoints' request and maps its response to the sent IntelliJ breakpoints.
     * <p>
     * The DAP response returns the breakpoints in the same order as the request, so the breakpoint at
     * index i belongs to the IntelliJ breakpoint sent at index i. It must not be searched by its line:
     * a debug adapter can move a breakpoint to another line (ex: from a blank line to the next statement).
     *
     * @param debugProtocolServer the debug adapter.
     * @param arguments           the 'setBreakpoints' arguments.
     * @param sentBreakpoints     the IntelliJ breakpoints, at the index of their DAP source breakpoint
     *                            (null for a temporary breakpoint).
     * @return the completable future.
     */
    private CompletableFuture<Void> setBreakpoints(@NotNull IDebugProtocolServer debugProtocolServer,
                                                   @NotNull SetBreakpointsArguments arguments,
                                                   @NotNull List<@Nullable B> sentBreakpoints) {
        CompletableFuture<SetBreakpointsResponse> future = debugProtocolServer.setBreakpoints(arguments);
        return future
                .thenAccept(response -> {
                    var breakpoints = response.getBreakpoints();
                    if (breakpoints == null) {
                        return;
                    }
                    for (int i = 0; i < breakpoints.length && i < sentBreakpoints.size(); i++) {
                        B ijBreakpoint = sentBreakpoints.get(i);
                        if (ijBreakpoint == null || !this.breakpoints.contains(ijBreakpoint)) {
                            // Temporary breakpoint, or IntelliJ breakpoint removed in the meantime
                            continue;
                        }
                        Breakpoint breakpointResponse = breakpoints[i];
                        ijBreakpoint.putUserData(DAP_BREAKPOINT_ID, breakpointResponse.getId());
                        updateBreakpoint(breakpointResponse, ijBreakpoint);
                    }
                });
    }

    private <B extends XBreakpoint<?>> boolean supportsBreakpointType(B breakpoint) {
        return breakpoint.getType() instanceof DAPBreakpointTypeBase<?>;
    }

    /**
     * Returns the IntelliJ breakpoint from the DAP stack frame and null otherwise.
     *
     * @param stackFrame the DAP stack frame.
     * @return the IntelliJ breakpoint from the DAP stack frame and null otherwise.
     */
    @Nullable
    public B findBreakpoint(@NotNull StackFrame stackFrame) {
        return findBreakpoint(stackFrame.getSource(), stackFrame.getLine());
    }

    /**
     * Returns the IntelliJ breakpoint reached by the given DAP 'stopped' event and null otherwise.
     * <p>
     * The breakpoint is searched by the 'hitBreakpointIds' of the event first: they identify the reached
     * breakpoint even when the debug adapter moved it to another line. Without ids, or if no IntelliJ
     * breakpoint has one of these ids (ex: a temporary breakpoint), it is searched by the source and line
     * of the top stack frame.
     *
     * @param args       the DAP 'stopped' event.
     * @param stackFrame the top DAP stack frame.
     * @return the IntelliJ breakpoint reached by the given DAP 'stopped' event and null otherwise.
     */
    @Nullable
    public B findBreakpoint(@NotNull StoppedEventArguments args,
                            @NotNull StackFrame stackFrame) {
        Integer[] hitBreakpointIds = args.getHitBreakpointIds();
        if (hitBreakpointIds != null) {
            for (Integer id : hitBreakpointIds) {
                List<B> hitBreakpoints = findBreakpointsById(id);
                if (!hitBreakpoints.isEmpty()) {
                    return hitBreakpoints.get(0);
                }
            }
        }
        return findBreakpoint(stackFrame);
    }

    /**
     * Returns the IntelliJ breakpoint from the DAP source and given line and null otherwise.
     * <p>
     * A breakpoint on the given line is returned first. Otherwise, a breakpoint which the debug adapter
     * moved to the given line (see {@link #DAP_BREAKPOINT_LINE}) is returned.
     *
     * @param source     the DAP source.
     * @param lineNumber the line number.
     * @return the IntelliJ breakpoint from the DAP source and given line and null otherwise.
     */
    @Nullable
    private B findBreakpoint(@Nullable Source source,
                             int lineNumber) {
        if (source == null) {
            return null;
        }
        Path filePath = getValidFilePath(source);
        if (filePath == null) {
            return null;
        }
        B movedBreakpoint = null;
        for (B breakpoint : breakpoints) {
            XSourcePosition breakpointPosition = breakpoint.getSourcePosition();
            if (breakpointPosition == null || !isSameFile(breakpointPosition.getFile(), filePath)) {
                continue;
            }
            if (breakpointPosition.getLine() + 1 == lineNumber) {
                return breakpoint;
            }
            if (movedBreakpoint == null && Integer.valueOf(lineNumber).equals(breakpoint.getUserData(DAP_BREAKPOINT_LINE))) {
                movedBreakpoint = breakpoint;
            }
        }
        return movedBreakpoint;
    }

    private static boolean isSameFile(@NotNull VirtualFile fileInBreakpoint,
                                      @NotNull Path filePath) {
        if (fileInBreakpoint.isInLocalFileSystem()) {
            return fileInBreakpoint.toNioPath().equals(filePath);
        }
        // Virtual files (e.g. DAPFile backed by LightVirtualFile) don't support toNioPath().
        // Fall back to comparing the VirtualFile path as a string. See #1447.
        return fileInBreakpoint.getPath().equals(filePath.toString());
    }

    /**
     * Returns the exception breakpoints panel which host exception breakpoint filter
     * get during the initialization of the DAP server.
     *
     * @return the exception breakpoints panel which host exception breakpoint filter
     * * get during the initialization of the DAP server.
     */
    public @NotNull DAPExceptionBreakpointsPanel getExceptionBreakpointsPanel() {
        return exceptionBreakpointsPanel;
    }

    /**
     * Returns the DAP server descriptor.
     *
     * @return the DAP server descriptor.
     */
    public @NotNull DebugAdapterDescriptor getDebugAdapterDescriptor() {
        return debugAdapterDescriptor;
    }

    /**
     * Send exception breakpoint filters to the all initialized DAP servers.
     */
    public void sendExceptionBreakpointFilters() {
        var applicableFilters = getExceptionBreakpointsPanel().getApplicableFilters();
        for (var server : debugProtocolServers) {
            sendExceptionBreakpointFilters(applicableFilters, server);
        }
    }

    /**
     * Send exception breakpoint filters to the given server.
     *
     * @param filters             all filters (enabled/disabled) coming from the DAP server.
     * @param debugProtocolServer the DAP server.
     * @return the future.
     */
    public CompletableFuture<Void> sendExceptionBreakpointFilters(ExceptionBreakpointsFilter[] filters,
                                                                  IDebugProtocolServer debugProtocolServer) {
        var applicableFilters = getExceptionBreakpointsPanel()
                .refresh(filters);
        return sendExceptionBreakpointFilters(
                applicableFilters, debugProtocolServer);
    }

    /**
     * Send exception breakpoint filters to the given server.
     *
     * @param applicableFilters   the applicable exception breakpoint filters.
     * @param debugProtocolServer the DAP server.
     * @return the future.
     */
    private CompletableFuture<Void> sendExceptionBreakpointFilters(Collection<ExceptionBreakpointsFilter> applicableFilters,
                                                                   IDebugProtocolServer debugProtocolServer) {
        SetExceptionBreakpointsArguments args = new SetExceptionBreakpointsArguments();
        args.setFilters(
                applicableFilters
                        .stream()
                        .map(ExceptionBreakpointsFilter::getFilter)
                        .toArray(String[]::new));
        return debugProtocolServer
                .setExceptionBreakpoints(args)
                .thenAccept(r -> {
                });
    }

}
