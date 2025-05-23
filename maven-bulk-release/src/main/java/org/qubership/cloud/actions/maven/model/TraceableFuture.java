package org.qubership.cloud.actions.maven.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.PipedOutputStream;
import java.util.concurrent.Future;

@Data
@AllArgsConstructor
public class TraceableFuture<T> {
    Future<T> future;
    PipedOutputStream outputStream;
}
