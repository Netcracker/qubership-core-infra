# Keep the Go toolchain and golangci-lint compatible with the modules we lint.
FROM docker.io/library/golang:1.27.2-alpine AS go_toolchain
FROM docker.io/golangci/golangci-lint:v2.15.0-alpine AS go_linter

FROM ghcr.io/super-linter/super-linter:slim-v9.0.0

ENV GOMODCACHE=/tmp/lint/.go/pkg/mod \
    GOTOOLCHAIN=local \
    PATH="/usr/local/go/bin:${PATH}"

USER root
COPY --from=go_toolchain /usr/local/go /usr/local/go
COPY --from=go_linter /usr/bin/golangci-lint /usr/bin/golangci-lint
RUN go version && golangci-lint --version

COPY prechecks/base-linters /etc/platform-ci-toolkit/base-liners
COPY --chmod=755 mandatory.py /usr/local/bin/lint
ENV VALIDATE_GO_MODULES=true \
    VALIDATE_PYTHON_RUFF=true \
    VALIDATE_JAVA=true \
    VALIDATE_BASH=true \
    VALIDATE_DOCKERFILE_HADOLINT=true \
    VALIDATE_YAML=true \
    VALIDATE_MARKDOWN=true
# Record the image's required flags before consumers can override the environment.
RUN env | sed -n 's/^\(VALIDATE_[A-Z0-9_]*\)=true$/\1/p' | sort \
    > /etc/platform-ci-toolkit/required-validators \
    && test -s /etc/platform-ci-toolkit/required-validators
# Super-Linter 9.0.0 reuses fixed private file lists across calls. Allow the
# mandatory CLI to supply its own directory while retaining the legacy default.
RUN sed -i 's|^SUPER_LINTER_PRIVATE_OUTPUT_DIRECTORY_PATH=.*|SUPER_LINTER_PRIVATE_OUTPUT_DIRECTORY_PATH="${TOOLKIT_LINTER_OUTPUT_DIR:-/tmp/${DEFAULT_SUPER_LINTER_OUTPUT_DIRECTORY_NAME}}"|' /action/lib/linter.sh \
    && grep -Fq 'TOOLKIT_LINTER_OUTPUT_DIR' /action/lib/linter.sh

RUN helm version --short && lint --help

ENTRYPOINT ["/usr/local/bin/lint"]
CMD ["--path", "/tmp/lint"]
