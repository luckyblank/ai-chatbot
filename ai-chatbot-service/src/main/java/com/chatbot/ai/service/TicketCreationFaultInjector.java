package com.chatbot.ai.service;

import com.chatbot.ai.domain.action.PendingAction;
import com.chatbot.ai.repository.CustomerServiceDataRepository;

/** Test extension point for proving rollback after the ticket INSERT. */
@FunctionalInterface
public interface TicketCreationFaultInjector {
    void afterTicketInserted(PendingAction action,
                             CustomerServiceDataRepository.CreatedTicket ticket);
}
