package org.qubership.cloud.actions.maven.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.concurrent.Future;

@Data
@AllArgsConstructor
public class FutureWrapper<T, S> {
    Future<T> future;
    S object;
}
