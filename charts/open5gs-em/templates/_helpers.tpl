{{- define "open5gs-em.name" -}}
{{- default (.Chart.Name | trimSuffix "-chart") .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "open5gs-em.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := include "open5gs-em.name" . }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{- define "open5gs-em.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "open5gs-em.selectorLabels" -}}
app.kubernetes.io/name: {{ include "open5gs-em.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "open5gs-em.labels" -}}
helm.sh/chart: {{ include "open5gs-em.chart" . }}
{{ include "open5gs-em.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: tep
{{- end }}

{{- define "open5gs-em.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "open5gs-em.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{- define "open5gs-em.mongodbSecretName" -}}
{{- default (include "open5gs-em.fullname" .) .Values.mongodb.existingSecret }}
{{- end }}

{{- /*
The Open5GS endpoints and the Mongo URI are templates, so a chart that deploys Open5GS too can point them at its
own release, e.g. "http://{{ .Release.Name }}-pcf-sbi:7777/npcf-policyauthorization/v1"
*/}}
{{- define "open5gs-em.pcfBaseUrl" -}}
{{- tpl (required "config.pcfBaseUrl is required" .Values.config.pcfBaseUrl) . }}
{{- end }}

{{- define "open5gs-em.amfMetricsUrl" -}}
{{- tpl (required "config.amfMetricsUrl is required" .Values.config.amfMetricsUrl) . }}
{{- end }}

{{- define "open5gs-em.smfMetricsUrl" -}}
{{- tpl (required "config.smfMetricsUrl is required" .Values.config.smfMetricsUrl) . }}
{{- end }}

{{- define "open5gs-em.mongodbUri" -}}
{{- tpl (required "mongodb.uri or mongodb.existingSecret is required" .Values.mongodb.uri) . }}
{{- end }}

{{- /* Where the PCF posts session notifications: config.pcfNotificationUrl, else this release's Service */}}
{{- define "open5gs-em.pcfNotificationUrl" -}}
{{- default (printf "http://%s.%s.svc:%v/notifications" (include "open5gs-em.fullname" .) .Release.Namespace .Values.service.port) (tpl .Values.config.pcfNotificationUrl .) }}
{{- end }}

{{- /* qualityProfiles, else global.qualityProfiles (set by a chart that also renders the PCF's qos_profiles from them) */}}
{{- define "open5gs-em.qualityProfiles" -}}
{{- toYaml (.Values.qualityProfiles | default (.Values.global | default dict).qualityProfiles | default list) }}
{{- end }}

{{- define "open5gs-em.image" -}}
{{- printf "%s:%s" .Values.image.repository (default .Chart.AppVersion .Values.image.tag) }}
{{- end }}
