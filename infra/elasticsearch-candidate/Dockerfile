ARG ELASTICSEARCH9_BASE_IMAGE=docker.elastic.co/elasticsearch/elasticsearch:9.3.2@sha256:55a94c7ad4767f4aaa03e75d825b782bf3009a2949a34111d023b7ab43343603
FROM ${ELASTICSEARCH9_BASE_IMAGE}

ARG ELASTICSEARCH9_BASE_IMAGE
ARG ELASTICSEARCH_VERSION=9.3.2
ARG IK_PLUGIN_URL
ARG IK_PLUGIN_SHA256

USER root
RUN test -n "${IK_PLUGIN_URL}" \
    && test -n "${IK_PLUGIN_SHA256}" \
    && curl --fail --silent --show-error --location --retry 3 \
        --connect-timeout 10 --max-time 120 \
        "${IK_PLUGIN_URL}" --output /tmp/analysis-ik.zip \
    && printf '%s  %s\n' "${IK_PLUGIN_SHA256}" /tmp/analysis-ik.zip | sha256sum --check --strict \
    && /usr/share/elasticsearch/bin/elasticsearch-plugin install --batch file:///tmp/analysis-ik.zip \
    && grep -Fxq "version=${ELASTICSEARCH_VERSION}" \
        /usr/share/elasticsearch/plugins/analysis-ik/plugin-descriptor.properties \
    && grep -Fxq "elasticsearch.version=${ELASTICSEARCH_VERSION}" \
        /usr/share/elasticsearch/plugins/analysis-ik/plugin-descriptor.properties \
    && rm -f /tmp/analysis-ik.zip \
    && chown -R elasticsearch:root /usr/share/elasticsearch/plugins/analysis-ik

LABEL org.opencontainers.image.title="AIDiag Elasticsearch IK candidate" \
      org.opencontainers.image.version="9.3.2" \
      aidiag.elasticsearch.base-image="${ELASTICSEARCH9_BASE_IMAGE}" \
      aidiag.elasticsearch.version="9.3.2" \
      aidiag.analysis-ik.version="9.3.2" \
      aidiag.analysis-ik.sha256="${IK_PLUGIN_SHA256}"

USER 1000
