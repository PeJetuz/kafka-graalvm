#https://github.com/rycus86/docker-intellij-idea
FROM vegardit/graalvm-maven:23.0.2

RUN  \
  apt-get update && apt-get install --no-install-recommends -y \
  git openssh-client less curl \
  libxtst-dev libxext-dev libxrender-dev libfreetype6-dev \
  libfontconfig1 libgtk2.0-0 libxslt1.1 libxxf86vm1 \
  sudo net-tools wget unzip zip iputils-ping \
  libsnappy-java \
  && ln -s /usr/lib/x86_64-linux-gnu/jni/libsnappyjava.so /usr/lib/libsnappyjava.so \
  && echo 'Cleaning up' \
  && rm -rf /var/lib/apt/lists/* \
  && useradd -ms /bin/bash dev

ARG TARGETARCH
ARG IDEA_VERSION=2024.3.4
ARG IDEA_BUILD=2024.3.4
ARG idea_local_dir=.IdeaIC${IDEA_VERSION}

RUN echo 'Creating user: dev' && \
    mkdir -p /home/dev && \
    echo "dev:x:1001:1001:dev,,,:/home/dev:/bin/bash" >> /etc/passwd && \
    echo "dev:x:1001:" >> /etc/group && \
	sudo echo "dev ALL=(ALL) NOPASSWD: ALL" > /etc/sudoers.d/dev && \
    sudo chmod 0440 /etc/sudoers.d/dev && \
    sudo chown dev:dev -R /home/dev && \
    sudo chown root:root /usr/bin/sudo && \
    chmod 4755 /usr/bin/sudo

WORKDIR /opt/idea

RUN echo "Preparing IntelliJ IDEA ${IDEA_BUILD} ..." \
  && if [ "$TARGETARCH" = "arm64" ]; then export idea_arch='-aarch64'; else export idea_arch=''; fi \
  && export idea_source=https://download.jetbrains.com/idea/ideaIC-${IDEA_BUILD}${idea_arch}.tar.gz \
  && echo "Downloading ${idea_source} ..." \
  && curl -fsSL $idea_source -o /opt/idea/installer.tgz \
  && tar --strip-components=1 -xzf installer.tgz \
  && rm installer.tgz
  
USER dev
ENV HOME=/home/dev
ENV APP_KAFKA_BOOTSTRAP_SERVERS=kafka-server2:9092
ENV APP_KAFKA_SCHEMA_REGISTRY_URL=http://schema-registry:8081
ENV IDEA_HOME=/opt/idea
ENV PATH="$IDEA_HOME/bin:$PATH"
WORKDIR /home/dev

RUN mkdir /home/dev/.Idea \
  && ln -sf /home/dev/.Idea /home/dev/$idea_local_dir
  
#CMD [ "/opt/idea/bin/idea.sh" ]
ENTRYPOINT [ "bash" ]