{{/* vim: set filetype=mustache: */}}
{{/*
Expand the name of the chart.
*/}}
{{- define "raoa.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 43 | trimSuffix "-" -}}
{{- end -}}

{{/*
Create a default fully qualified app name.
We truncate at 63 chars because some Kubernetes name fields are limited to this (by the DNS naming spec).
If release name contains chart name it will be used as a full name.
*/}}
{{- define "raoa.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "raoa.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Common labels
*/}}
{{- define "raoa.labels" -}}
helm.sh/chart: {{ include "raoa.chart" . }}
{{ include "raoa.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{/*
Selector labels
*/}}
{{- define "raoa.selectorLabels" -}}
app.kubernetes.io/name: {{ include "raoa.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
{{- define "raoa.viewerSelectorLabels" -}}
{{ include "raoa.selectorLabels" . }}
app.kubernetes.io/part: viewer
{{- end -}}
{{- define "raoa.coordinatorSelectorLabels" -}}
app.kubernetes.io/name: {{ include "raoa.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/part: coordinator
{{- end -}}
{{- define "raoa.imageProcessorSelectorLabels" -}}
app.kubernetes.io/name: {{ include "raoa.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/part: image-processor
{{- end -}}
{{/*
Create the name of the service account to use
*/}}
{{- define "raoa.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
    {{ default (include "raoa.fullname" .) .Values.serviceAccount.name }}
{{- else -}}
    {{ default "default" .Values.serviceAccount.name }}
{{- end -}}
{{- end -}}

{{- define "raoa.dataVolume" -}}
{{- if .Values.data.repository.override -}}
{{ toYaml .Values.data.repository.definition }}
{{- else -}}
persistentVolumeClaim:
  claimName: {{ include "raoa.name" .}}-repository
{{- end -}}
{{- end -}}

{{- define "raoa.cacheVolume" -}}
{{- if .Values.data.cache.override -}}
{{ toYaml .Values.data.cache.definition }}
{{- else -}}
persistentVolumeClaim:
  claimName: {{ include "raoa.name" . }}-cache
{{- end -}}
{{- end -}}

{{- define "raoa.tls.name" -}}
{{- printf "%s-tls" .Values.ingress.host | replace "." "-" | trunc 63 -}}
{{- end -}}


{{- define "raoa.common.config" -}}
management.endpoints.web.exposure.include: "*"
management.server.port: {{.Values.managementPort | quote}}
server.port: {{.Values.containerPort | quote}}
grpc.port: {{.Values.grpcPort | quote}}
management.endpoint.health.show-details: always
management.endpoint.health.show-components: always
raoa.repository: /data
raoa.thumbnailDir: /cache
raoa.max-concurrent: "20"
server.use-forward-headers: "true"
spring.data.elasticsearch.client.reactive.endpoints: {{ include "raoa.fullname" . }}-es-http:9200
spring.data.elasticsearch.client.reactive.useSsl: "true"
spring.data.elasticsearch.client.reactive.username: elastic
spring.data.elasticsearch.client.reactive.socket-timeout: 1m
spring.elasticsearch.rest.uris: https://{{ include "raoa.fullname" . }}-es-http:9200
spring.elasticsearch.rest.username: elastic
#logging.level.org.springframework.data.elasticsearch.client.WIRE: trace
#logging.level.org.apache.kafka: debug
{{- end -}}
