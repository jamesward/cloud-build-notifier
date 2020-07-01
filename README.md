# Cloud Build Notifier

Receive email notifications when your Cloud Builds succeed or fail.

Deploy:

[![Run on Google Cloud](https://deploy.cloud.run/button.svg)](https://deploy.cloud.run)

Run Locally:
1. Fork [github.com/jamesward/cloud-build-test](https://github.com/jamesward/cloud-build-test)
1. [Setup a build trigger for your forked hello-netcat repo](https://console.cloud.google.com/cloud-build/triggers)
1. Copy the name of your new build trigger for later use
1. Create a service account and download a key
    ```
    export GOOGLE_CLOUD_PROJECT=YOUR_GCLOUD_PROJECT
   
    gcloud iam service-accounts create cloud-build-notifier-dev \
      --project=$GOOGLE_CLOUD_PROJECT
   
    gcloud projects add-iam-policy-binding $GOOGLE_CLOUD_PROJECT \
      --member=serviceAccount:cloud-build-notifier-dev@${GOOGLE_CLOUD_PROJECT}.iam.gserviceaccount.com \
      --role=roles/monitoring.metricWriter &> /dev/null
   
    gcloud iam service-accounts keys create /tmp/cloud-build-notifier-dev.json --iam-account=cloud-build-notifier-dev@${GOOGLE_CLOUD_PROJECT}.iam.gserviceaccount.com
    ```

1. 
1. Start the app:
    ```
    GOOGLE_APPLICATION_CREDENTIALS=/tmp/cloud-build-notifier-dev.json ./gradlew -t run
    ```
1. [Download ngrok](https://ngrok.com/download)
1. `ngrok http 8080`
1. Setup a Cloud Build Pub/Sub subscription
    ```
    export GOOGLE_CLOUD_PROJECT=YOUR_GCLOUD_PROJECT
    export ENDPOINT_URL=YOUR_NGROK_URL
    export K_SERVICE=cloud-build-notifier-dev
    gcloud/setup-pubsub.sh
    ```
1. Run a successful build and a build that fails:
    ```
    export GOOGLE_CLOUD_PROJECT=YOUR_GCLOUD_PROJECT
    export BUILD_TRIGGER=YOUR_BUILD_TRIGGER
    gcloud beta builds --project=$GOOGLE_CLOUD_PROJECT triggers run $BUILD_TRIGGER --branch=main
    gcloud beta builds --project=$GOOGLE_CLOUD_PROJECT triggers run $BUILD_TRIGGER --branch=fail
    ```

Containerize Locally as a GraalVM native image:
```
docker build -t cloud-build-notifier .
```

Run container:
```
docker run -p8080:8080 cloud-build-notifier
```

