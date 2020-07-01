#!/bin/bash

if [[ -z "${GOOGLE_CLOUD_PROJECT}" ]]; then
  echo "You must set the GOOGLE_CLOUD_PROJECT env var"
fi

if [[ -z "${GOOGLE_CLOUD_REGION}" ]] && [[ -z "${ENDPOINT_URL}" ]]; then
  echo "You must set the GOOGLE_CLOUD_REGION env var"
else
  declare region=${GOOGLE_CLOUD_REGION}
fi

if [[ -z "${K_SERVICE}" ]]; then
  echo "You must set the K_SERVICE env var"
fi

declare project=${GOOGLE_CLOUD_PROJECT}
declare service=${K_SERVICE}

if [[ -z "${ENDPOINT_URL}" ]]; then
  declare endpoint=$(gcloud run services describe $service --platform=managed --region=$region --project=$project --format="value(status.address.url)")
else
  declare endpoint=${ENDPOINT_URL}
fi

gcloud services enable --project="${project}" cloudbuild.googleapis.com

# todo: verify cloud-build pub/sub topic exists

gcloud pubsub subscriptions create cloud-build-notifier-${service} \
  --topic=cloud-builds \
  --push-endpoint="${endpoint}"
