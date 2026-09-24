# Use amd64 platform for Android build tools compatibility (runs under
# Rosetta on Apple Silicon; slower but correct).
FROM --platform=linux/amd64 eclipse-temurin:21-jdk

ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_NDK_HOME=${ANDROID_HOME}/ndk/28.2.13676358
ENV PATH="${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/build-tools/35.0.0"

# Install required packages (build-essential/pkg-config/perl for building
# Rust native deps such as rusqlite's bundled SQLite C code and ring's
# assembly for rustls, even though the final cross-compile happens with the
# NDK toolchain).
# Retries + a fresh `update` per attempt work around a flaky archive mirror
# that intermittently drops mid-fetch on an otherwise-successful apt run.
RUN echo 'Acquire::Retries "5";' > /etc/apt/apt.conf.d/80-retries && \
    for i in 1 2 3 4 5; do \
        apt-get update && apt-get install -y \
            unzip \
            wget \
            git \
            curl \
            build-essential \
            pkg-config \
            perl \
        && break || { echo "apt-get attempt $i failed, retrying..."; sleep 5; }; \
    done && rm -rf /var/lib/apt/lists/*

# Download and install Android command line tools
RUN mkdir -p ${ANDROID_HOME}/cmdline-tools && \
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O /tmp/cmdline-tools.zip && \
    unzip -q /tmp/cmdline-tools.zip -d ${ANDROID_HOME}/cmdline-tools && \
    mv ${ANDROID_HOME}/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest && \
    rm /tmp/cmdline-tools.zip

# Accept licenses
RUN yes | sdkmanager --licenses

# Install Android SDK components + NDK.
# 28.2.13676358 is the newest stable (non-rc) NDK sdkmanager offered at the
# time this image was built; verified with `sdkmanager --list | grep ndk`.
RUN sdkmanager \
    "platforms;android-36" \
    "build-tools;35.0.0" \
    "platform-tools" \
    "ndk;28.2.13676358"

# Rust toolchain via rustup, with Android cross-compile targets and cargo-ndk.
ENV RUSTUP_HOME=/opt/rustup
ENV CARGO_HOME=/opt/cargo
ENV PATH="${PATH}:/opt/cargo/bin"

RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | \
    sh -s -- -y --profile minimal --default-toolchain 1.98.1 && \
    rustup component add rustfmt clippy && \
    rustup target add aarch64-linux-android x86_64-linux-android armv7-linux-androideabi && \
    cargo install cargo-ndk --version 4.1.2 --locked

WORKDIR /app

# Set Gradle user home to a directory inside the project for caching
ENV GRADLE_USER_HOME=/app/.gradle-home
