package com.aegis.alpha.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Constructor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guard for P0-1: with several public constructors Spring can only instantiate ChatService
 * when exactly one of them is marked {@link Autowired}; it must be the widest one so the
 * production bean never runs with null collaborators.
 */
class ChatServiceConstructorTest {

    @Test
    void exactlyOneConstructorIsAutowiredAndItIsTheWidest() {
        Constructor<?> autowired = null;
        int autowiredCount = 0;
        int widest = 0;
        for (Constructor<?> constructor : ChatService.class.getConstructors()) {
            widest = Math.max(widest, constructor.getParameterCount());
            if (constructor.isAnnotationPresent(Autowired.class)) {
                autowired = constructor;
                autowiredCount++;
            }
        }
        assertThat(autowiredCount).isEqualTo(1);
        assertThat(autowired.getParameterCount()).isEqualTo(widest);
    }
}
