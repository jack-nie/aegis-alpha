package com.aegis.alpha.service;

import com.aegis.alpha.domain.WorkflowRun;
import com.aegis.alpha.mapper.WorkflowMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/** P4-4: emulate {@link WorkflowMapper#updateRunStatusIf} (status CAS + no-cancel guard) over an in-memory run. */
final class CasMapperStubs {
    private CasMapperStubs() {
    }

    @SuppressWarnings("unchecked")
    static void casOn(WorkflowMapper mapper, AtomicReference<WorkflowRun> stored, UnaryOperator<WorkflowRun> copier) {
        when(mapper.updateRunStatusIf(any(WorkflowRun.class), anyList(), anyBoolean())).thenAnswer(inv -> {
            WorkflowRun current = stored.get();
            List<String> expected = inv.getArgument(1);
            boolean requireNoCancel = inv.getArgument(2);
            if (current == null || !expected.contains(current.getStatus())) {
                return 0;
            }
            if (requireNoCancel && current.getCancelRequested() != null && current.getCancelRequested() != 0) {
                return 0;
            }
            stored.set(copier.apply(inv.getArgument(0)));
            return 1;
        });
    }
}
