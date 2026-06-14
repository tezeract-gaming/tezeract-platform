#!/bin/bash
# =============================================================================
# TEZERACT MAC HELPER SCRIPT v1.0
# Run this on your Mac to convert the model and deploy to the Pi
# Prerequisites: pyenv, Python 3.10, Google Colab account
# Usage: chmod +x tezeract_mac_setup.sh && ./tezeract_mac_setup.sh
# =============================================================================

set -e

PURPLE='\033[0;35m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log()  { echo -e "${PURPLE}[MAC]${NC} $1"; }
ok()   { echo -e "${GREEN}[OK]${NC} $1"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }

echo ""
echo -e "${PURPLE}  TEZERACT — Mac Setup Helper v1.0${NC}"
echo ""

# =============================================================================
# STEP 1 — GET PI IP
# =============================================================================
read -p "Enter your Orange Pi IP address (e.g. 192.168.0.25): " PI_IP

# Test connection
log "Testing connection to Pi at $PI_IP..."
if ssh -o ConnectTimeout=5 orangepi@$PI_IP "echo connected" &>/dev/null; then
    ok "Pi is reachable at $PI_IP"
else
    warn "Cannot reach Pi. Check IP and make sure SSH is enabled."
    exit 1
fi

# =============================================================================
# STEP 2 — CHECK FOR RKNN MODEL
# =============================================================================
log "Checking for converted RKNN model..."

if [ ! -f ~/Downloads/yolov8n-pose.rknn ] && [ ! -f ~/Desktop/yolov8n-pose.rknn ]; then
    warn "yolov8n-pose.rknn not found in Downloads or Desktop."
    echo ""
    echo "  Convert it using Google Colab:"
    echo "  1. Go to colab.research.google.com"
    echo "  2. Create a new notebook and run these cells:"
    echo ""
    echo "  ---- Cell 1 ----"
    echo "  !pip install rknn-toolkit2 onnx==1.16.1"
    echo ""
    echo "  ---- Cell 2 ----"
    echo '  !curl -L -o yolov8n-pose.onnx "https://ftrg.zbox.filez.com/v2/delivery/data/95f00b0fc900458ba134f8b180b3f7a1/examples/yolov8_pose/yolov8n-pose.onnx"'
    echo ""
    echo "  ---- Cell 3 ----"
    echo "  from rknn.api import RKNN"
    echo "  rknn = RKNN()"
    echo "  rknn.config(target_platform='rk3588')"
    echo "  rknn.load_onnx(model='yolov8n-pose.onnx')"
    echo "  rknn.build(do_quantization=False)"
    echo "  rknn.export_rknn('yolov8n-pose.rknn')"
    echo "  rknn.release()"
    echo "  print('Done!')"
    echo ""
    echo "  ---- Cell 4 ----"
    echo "  from google.colab import files"
    echo "  files.download('yolov8n-pose.rknn')"
    echo ""
    read -p "Press Enter when yolov8n-pose.rknn is in your Downloads folder..."
fi

# Find the model
MODEL_PATH=""
[ -f ~/Downloads/yolov8n-pose.rknn ] && MODEL_PATH=~/Downloads/yolov8n-pose.rknn
[ -f ~/Desktop/yolov8n-pose.rknn ]   && MODEL_PATH=~/Desktop/yolov8n-pose.rknn

if [ -z "$MODEL_PATH" ]; then
    warn "Still can't find yolov8n-pose.rknn. Exiting."
    exit 1
fi
ok "Model found at $MODEL_PATH"

# =============================================================================
# STEP 3 — COPY BOOTSTRAP SCRIPT TO PI
# =============================================================================
log "Copying bootstrap script to Pi..."

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
scp "$SCRIPT_DIR/tezeract_setup.sh" orangepi@$PI_IP:~/
ssh orangepi@$PI_IP "chmod +x ~/tezeract_setup.sh"
ok "Bootstrap script copied"

# =============================================================================
# STEP 4 — COPY MODEL TO PI
# =============================================================================
log "Creating models directory and copying RKNN model..."
ssh orangepi@$PI_IP "mkdir -p ~/models"
scp "$MODEL_PATH" orangepi@$PI_IP:~/models/yolov8n-pose.rknn
ok "Model deployed to Pi"

# =============================================================================
# STEP 5 — RUN BOOTSTRAP ON PI
# =============================================================================
log "Running bootstrap script on Pi (this will take 5-10 minutes)..."
echo ""
warn "Watch the Pi output. You may be prompted for the sudo password: orangepi"
echo ""

ssh -t orangepi@$PI_IP "~/tezeract_setup.sh"

# =============================================================================
# DONE
# =============================================================================
echo ""
echo -e "${PURPLE}=================================================${NC}"
echo -e "${GREEN}  DEPLOYMENT COMPLETE!${NC}"
echo -e "${PURPLE}=================================================${NC}"
echo ""
echo "  To run the live pipeline:"
echo "  ssh orangepi@$PI_IP 'DISPLAY=:0.0 python3 ~/tezeract_live.py'"
echo ""
echo "  To stop it:"
echo "  ssh orangepi@$PI_IP 'pkill -f tezeract_live.py'"
echo ""
echo -e "${PURPLE}  Tezeract is ready. 🚀${NC}"
echo ""
