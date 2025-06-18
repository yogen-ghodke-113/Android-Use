# Deployment Guide

This guide will help you deploy your own Android-Use server to Google Cloud Run or other cloud platforms.

## 🚀 Google Cloud Run Deployment (Recommended)

Google Cloud Run is the easiest way to deploy the Android-Use server with automatic scaling and HTTPS support.

### Prerequisites

- Google Cloud Account with billing enabled
- [Google Cloud CLI](https://cloud.google.com/sdk/docs/install) installed
- Docker installed (for local testing)

### Step 1: Set Up Google Cloud Project

```bash
# Create a new project (or use existing one)
gcloud projects create android-use-server-PROJECT_ID
gcloud config set project android-use-server-PROJECT_ID

# Enable required APIs
gcloud services enable run.googleapis.com
gcloud services enable cloudbuild.googleapis.com
gcloud services enable artifactregistry.googleapis.com
gcloud services enable aiplatform.googleapis.com

# Set default region (choose one close to your users)
gcloud config set run/region us-central1
```

### Step 2: Set Up Authentication

#### Option A: Service Account (Recommended for production)

```bash
# Create service account
gcloud iam service-accounts create android-use-server \
    --display-name="Android Use Server"

# Grant necessary permissions
gcloud projects add-iam-policy-binding PROJECT_ID \
    --member="serviceAccount:android-use-server@PROJECT_ID.iam.gserviceaccount.com" \
    --role="roles/aiplatform.user"

# Create and download key
gcloud iam service-accounts keys create service-account-key.json \
    --iam-account=android-use-server@PROJECT_ID.iam.gserviceaccount.com
```

#### Option B: Gemini API Key (Simpler setup)

1. Go to [Google AI Studio](https://aistudio.google.com/app/apikey)
2. Create a new API key
3. Save it for later use

### Step 3: Deploy to Cloud Run

#### Quick Deploy (using source)

```bash
cd adk-droid

# Deploy directly from source
gcloud run deploy android-use-server \
    --source . \
    --platform managed \
    --region us-central1 \
    --allow-unauthenticated \
    --set-env-vars="GEMINI_API_KEY=your-api-key-here" \
    --memory=1Gi \
    --cpu=1 \
    --max-instances=10
```

#### Docker Deploy (for more control)

```bash
cd adk-droid

# Build and push to Artifact Registry
gcloud builds submit --tag gcr.io/PROJECT_ID/android-use-server

# Deploy the container
gcloud run deploy android-use-server \
    --image gcr.io/PROJECT_ID/android-use-server \
    --platform managed \
    --region us-central1 \
    --allow-unauthenticated \
    --set-env-vars="GEMINI_API_KEY=your-api-key-here" \
    --memory=1Gi \
    --cpu=1 \
    --max-instances=10
```

### Step 4: Configure Environment Variables

Set up environment variables for your deployment:

```bash
# Using Gemini API
gcloud run services update android-use-server \
    --set-env-vars="GEMINI_API_KEY=your-api-key-here,LOG_LEVEL=INFO"

# Or using Service Account (if you uploaded the key as a secret)
gcloud run services update android-use-server \
    --set-env-vars="GOOGLE_APPLICATION_CREDENTIALS=/app/service-account-key.json,GOOGLE_CLOUD_PROJECT=PROJECT_ID,LOG_LEVEL=INFO"
```

### Step 5: Get Your Server URL

```bash
# Get the service URL
gcloud run services describe android-use-server \
    --region=us-central1 \
    --format="value(status.url)"
```

The output will be something like: `https://android-use-server-xyz-uc.a.run.app`

### Step 6: Update Android App

Update the server URL in your Android app:

1. Open `app/src/main/java/com/yogen/Android_Use/utils/Constants.kt`
2. Replace the `SERVER_URL` with your Cloud Run URL:
   ```kotlin
   const val SERVER_URL = "wss://android-use-server-xyz-uc.a.run.app"
   ```
   Note: Use `wss://` instead of `ws://` for HTTPS endpoints.

## 🐳 Alternative Deployment Options

### Railway

1. Fork the repository on GitHub
2. Connect your GitHub account to [Railway](https://railway.app)
3. Deploy the `adk-droid` folder
4. Set environment variables in Railway dashboard
5. Use the provided URL in your Android app

### Heroku

```bash
cd adk-droid

# Create Heroku app
heroku create your-android-use-server

# Set environment variables
heroku config:set GEMINI_API_KEY=your-api-key-here
heroku config:set PORT=8080

# Deploy
git add .
git commit -m "Deploy to Heroku"
git push heroku main
```

### Render

1. Fork the repository on GitHub
2. Connect your GitHub account to [Render](https://render.com)
3. Create a new Web Service
4. Select the `adk-droid` folder as root directory
5. Set build command: `pip install -r requirements.txt`
6. Set start command: `python -m uvicorn main:app --host 0.0.0.0 --port $PORT`
7. Add environment variables in Render dashboard

### AWS EC2 / Azure VM / DigitalOcean

For virtual machine deployments:

```bash
# Install Docker
curl -fsSL https://get.docker.com -o get-docker.sh
sh get-docker.sh

# Clone and build
git clone https://github.com/your-username/android-use.git
cd android-use/adk-droid

# Create .env file with your configuration
cat > .env << EOF
GEMINI_API_KEY=your-api-key-here
LOG_LEVEL=INFO
PORT=8080
EOF

# Build and run
docker build -t android-use-server .
docker run -d -p 8080:8080 --env-file .env android-use-server
```

## 🔒 Security Considerations

### For Production Deployments

1. **Environment Variables**: Never hardcode API keys. Use secure environment variable storage.

2. **Authentication**: Consider adding authentication to your WebSocket endpoint:

   ```python
   # In main.py websocket_endpoint
   # Add authentication logic here
   ```

3. **CORS**: Update CORS settings for production:

   ```python
   app.add_middleware(
       CORSMiddleware,
       allow_origins=["https://your-domain.com"],  # Restrict origins
       allow_credentials=True,
       allow_methods=["GET", "POST"],
       allow_headers=["*"],
   )
   ```

4. **Rate Limiting**: Add rate limiting to prevent abuse:

   ```bash
   pip install slowapi
   ```

5. **Monitoring**: Set up logging and monitoring:
   ```bash
   # Google Cloud Logging is automatically enabled on Cloud Run
   # View logs with:
   gcloud logs read "resource.type=cloud_run_revision"
   ```

## 🧪 Testing Your Deployment

### Health Check

```bash
curl https://your-server-url/health
```

Expected response:

```json
{ "status": "healthy", "timestamp": "2025-01-XX..." }
```

### WebSocket Connection Test

Use a WebSocket client to test:

```javascript
const ws = new WebSocket("wss://your-server-url/ws/test-session");
ws.onopen = () => {
  console.log("Connected");
  ws.send(
    JSON.stringify({
      type: "ping",
      content: "test",
    })
  );
};
ws.onmessage = (event) => {
  console.log("Received:", event.data);
};
```

## 📊 Monitoring and Scaling

### Google Cloud Run

- **Auto-scaling**: Automatically scales based on traffic
- **Monitoring**: Use Google Cloud Console to monitor performance
- **Logs**: View real-time logs in Cloud Console
- **Metrics**: CPU, memory, and request metrics available

### Cost Optimization

```bash
# Set minimum instances to 0 for cost savings
gcloud run services update android-use-server \
    --min-instances=0 \
    --max-instances=10

# Use smaller CPU allocation for light usage
gcloud run services update android-use-server \
    --cpu=0.5 \
    --memory=512Mi
```

## 🛠️ Troubleshooting

### Common Issues

1. **Build Failures**:

   ```bash
   # Check build logs
   gcloud builds list
   gcloud builds log BUILD_ID
   ```

2. **Memory Issues**:

   ```bash
   # Increase memory allocation
   gcloud run services update android-use-server --memory=2Gi
   ```

3. **Cold Starts**:

   ```bash
   # Set minimum instances to reduce cold starts
   gcloud run services update android-use-server --min-instances=1
   ```

4. **Environment Variables**:
   ```bash
   # List current environment variables
   gcloud run services describe android-use-server --format="export"
   ```

### Debugging

```bash
# View logs
gcloud logs tail "resource.type=cloud_run_revision AND resource.labels.service_name=android-use-server"

# Connect to container for debugging
gcloud run services proxy android-use-server --port=8080
```

## 🔄 CI/CD Setup

### GitHub Actions

Create `.github/workflows/deploy.yml`:

```yaml
name: Deploy to Cloud Run

on:
  push:
    branches: [main]
    paths: ["adk-droid/**"]

jobs:
  deploy:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v3

      - id: "auth"
        uses: "google-github-actions/auth@v1"
        with:
          credentials_json: "${{ secrets.GCP_SA_KEY }}"

      - name: "Set up Cloud SDK"
        uses: "google-github-actions/setup-gcloud@v1"

      - name: "Deploy to Cloud Run"
        run: |
          cd adk-droid
          gcloud run deploy android-use-server \
            --source . \
            --region us-central1 \
            --allow-unauthenticated \
            --set-env-vars="GEMINI_API_KEY=${{ secrets.GEMINI_API_KEY }}"
```

Add these secrets to your GitHub repository:

- `GCP_SA_KEY`: Your service account JSON key
- `GEMINI_API_KEY`: Your Gemini API key

---

## 📞 Support

If you encounter issues with deployment:

1. Check the [Troubleshooting](#-troubleshooting) section above
2. Review Cloud Run logs for error details
3. Open an issue on GitHub with deployment logs
4. Join our discussions for community help

Happy deploying! 🚀
