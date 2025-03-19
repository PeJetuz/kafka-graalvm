# Preconditions
docker network create app-tier --driver bridge
docker run -d --name kafka-server2 --hostname kafka-server2 -p 9092:9092 -p 9093:9093 -p 9094:9094 \
--network app-tier \
-e KAFKA_CFG_NODE_ID=0 \
-e KAFKA_CFG_PROCESS_ROLES=controller,broker \
-e KAFKA_CFG_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093,EXTERNAL://0.0.0.0:9094 \
-e KAFKA_CFG_ADVERTISED_LISTENERS=PLAINTEXT://kafka-server2:9092,EXTERNAL://localhost:9094 \
-e KAFKA_CFG_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,EXTERNAL:PLAINTEXT,PLAINTEXT:PLAINTEXT \
-e KAFKA_CFG_CONTROLLER_QUORUM_VOTERS=0@kafka-server2:9093 \
-e KAFKA_CFG_CONTROLLER_LISTENER_NAMES=CONTROLLER \
bitnami/kafka:3.9.0

# https://hub.docker.com/r/bitnami/schema-registry
docker run -d --name schema-registry -p 8081:8081 \
--network app-tier \
--env SCHEMA_REGISTRY_DEBUG=true \
-e SCHEMA_REGISTRY_KAFKA_BROKERS=PLAINTEXT://kafka-server2:9092 \
-e SCHEMA_REGISTRY_LISTENERS=http://0.0.0.0:8081 \
bitnami/schema-registry:7.8.0


#build for alpine
mvn clean verify -Dpackaging=native-image -Dalpine

#Run integration test
mvn clean verify package -Dit

# run agent
export KAFKA_BOOTSTRAP_SERVERS=kafka-server2:9092
export SCHEMA_REGISTRY_URL=http://schema-registry:8081
mvn clean package
java -agentlib:native-image-agent=config-output-dir=conf -jar target/demo-0.1.jar

# open a new terminal and run a test to trace all branches in the server
cd src/it
mvn clean package && java -jar target/wsclient-0.1.jar
# after completion stop wsclient (press Ctrl+C) demo-0.1.jar
stop demo-0.1.jar
# for alpine remove section "glob": "linux/amd64/libzstd-jni-1.5.6-4.so"
# for alpine add section "glob": "org-xerial-snappy.properties"
copy conf/* to src/main/resources/META-INF/native-image/

# native
docker build -t micronaut-kafka -f Dockerfile.alpine .
#docker build -t micronaut-kafka --build-arg KAFKA_BOOTSTRAP_SERVERS=kafka-server2:9092 --build-arg SCHEMA_REGISTRY_URL=http://schema-registry:8081 -f Dockerfile.native .
docker run --rm --network app-tier -p 8080:8080 micronaut-kafka:latest
#docker run --rm --network app-tier -p 8080:8080 -e KAFKA_BOOTSTRAP_SERVERS=kafka-server2:9094 -e SCHEMA_REGISTRY_URL=http://schema-registry:8081 micronaut-kafka:latest

#Check https://guides.micronaut.io/latest/micronaut-websocket-maven-java.html
#Open a browser and visit a URL such as http://localhost:8080/#/java/Joe.
# Then in another browser tab, open http://localhost:8080/#/java/Moka.
# You can then try sending chats as Joe and Moka.


###########################################

#graalvm
docker exec -ti graal23 mc
docker run -it --name=graal --network=app-tier -v /c/prj/testme:/mnt/testme:rw -v /c/Users/User/.m2:/root/.m2:rw -w /mnt/testme ghcr.io/graalvm/native-image-community:23.0.2-muslib-ol9 mc



## Micronaut 4.8.0-SNAPSHOT Documentation

- [User Guide](https://docs.micronaut.io/snapshot/guide/index.html)
- [API Reference](https://docs.micronaut.io/snapshot/api/index.html)
- [Configuration Reference](https://docs.micronaut.io/snapshot/guide/configurationreference.html)
- [Micronaut Guides](https://guides.micronaut.io/index.html)
---

- [Micronaut Maven Plugin documentation](https://micronaut-projects.github.io/micronaut-maven-plugin/latest/)
## Feature serialization-jackson documentation

- [Micronaut Serialization Jackson Core documentation](https://micronaut-projects.github.io/micronaut-serialization/latest/guide/)


## Feature maven-enforcer-plugin documentation

- [https://maven.apache.org/enforcer/maven-enforcer-plugin/](https://maven.apache.org/enforcer/maven-enforcer-plugin/)


## Feature awaitility documentation

- [https://github.com/awaitility/awaitility](https://github.com/awaitility/awaitility)


## Feature validation documentation

- [Micronaut Validation documentation](https://micronaut-projects.github.io/micronaut-validation/latest/guide/)


## Feature micronaut-aot documentation

- [Micronaut AOT documentation](https://micronaut-projects.github.io/micronaut-aot/latest/guide/)


## Feature websocket documentation

- [Micronaut Websocket documentation](https://docs.micronaut.io/latest/guide/#websocket)


## Feature reactor documentation

- [Micronaut Reactor documentation](https://micronaut-projects.github.io/micronaut-reactor/snapshot/guide/index.html)


