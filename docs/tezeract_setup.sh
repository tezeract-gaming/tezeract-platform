#!/bin/bash
# =============================================================================
# TEZERACT POC BOOTSTRAP SCRIPT v1.0
# Orange Pi 5 Plus (RK3588) — RGB-Only Pose Tracking Setup
# Run this script on the Orange Pi after first boot and SSH connection
# Usage: chmod +x tezeract_setup.sh && ./tezeract_setup.sh
# =============================================================================

set -e  # Exit on any error

PURPLE='\033[0;35m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

log()    { echo -e "${PURPLE}[TEZERACT]${NC} $1"; }
ok()     { echo -e "${GREEN}[OK]${NC} $1"; }
warn()   { echo -e "${YELLOW}[WARN]${NC} $1"; }
error()  { echo -e "${RED}[ERROR]${NC} $1"; exit 1; }

echo ""
echo -e "${PURPLE}"
echo "  ████████╗███████╗███████╗███████╗██████╗  █████╗  ██████╗████████╗"
echo "     ██╔══╝██╔════╝╚════██║██╔════╝██╔══██╗██╔══██╗██╔════╝╚══██╔══╝"
echo "     ██║   █████╗      ██╔╝█████╗  ██████╔╝███████║██║        ██║   "
echo "     ██║   ██╔══╝     ██╔╝ ██╔══╝  ██╔══██╗██╔══██║██║        ██║   "
echo "     ██║   ███████╗   ██║  ███████╗██║  ██║██║  ██║╚██████╗   ██║   "
echo "     ╚═╝   ╚══════╝   ╚═╝  ╚══════╝╚═╝  ╚═╝╚═╝  ╚═╝ ╚═════╝  ╚═╝   "
echo -e "${NC}"
echo "  PoC Bootstrap v1.0 — Orange Pi 5 Plus RK3588"
echo "  RGB-Only Pose Tracking Pipeline"
echo ""

# =============================================================================
# PHASE 1 — SYSTEM UPDATE & CORE DEPENDENCIES
# =============================================================================
log "PHASE 1: System update and core dependencies..."

sudo apt update && sudo apt upgrade -y --allow-change-held-packages
sudo apt install -y --allow-change-held-packages \
    build-essential cmake git \
    python3-pip python3-venv \
    libopencv-dev python3-opencv \
    libusb-1.0-0-dev libudev-dev \
    v4l-utils \
    ffmpeg \
    libgl1 libglib2.0-0t64 \
    libgtk2.0-dev pkg-config

ok "System dependencies installed"

# =============================================================================
# PHASE 2 — ENABLE & HARDEN SSH
# =============================================================================
log "PHASE 2: Enabling SSH..."

sudo systemctl enable ssh
sudo systemctl start ssh

ok "SSH enabled and running"
warn "IMPORTANT: Change your password now with: passwd"

# =============================================================================
# PHASE 3 — VERIFY HARDWARE
# =============================================================================
log "PHASE 3: Verifying hardware..."

# Check NPU
NPU_STATUS=$(cat /sys/devices/platform/fdab0000.npu/power/runtime_status 2>/dev/null || echo "not found")
if [ "$NPU_STATUS" = "suspended" ] || [ "$NPU_STATUS" = "active" ]; then
    ok "NPU detected: $NPU_STATUS"
else
    error "NPU not found! Check your Orange Pi 5 Plus image."
fi

# Check camera
if v4l2-ctl --list-devices 2>/dev/null | grep -q "video0"; then
    ok "Camera detected at /dev/video0"
    v4l2-ctl -d /dev/video0 --list-formats-ext 2>/dev/null | grep -A2 "1280x720" && ok "720p/120fps confirmed" || warn "720p mode not confirmed, check camera"
else
    warn "Camera not detected. Make sure SVPRO is plugged into a blue USB 3.0 port"
fi

# =============================================================================
# PHASE 4 — PYTHON ML DEPENDENCIES
# =============================================================================
log "PHASE 4: Installing Python ML dependencies..."

pip3 install numpy onnxruntime opencv-python

ok "Python ML dependencies installed"

# =============================================================================
# PHASE 5 — RKNN TOOLKIT LITE (NPU RUNTIME)
# =============================================================================
log "PHASE 5: Installing RKNN Lite runtime..."

cd ~
if [ ! -d "rknn-toolkit2" ]; then
    git clone https://github.com/airockchip/rknn-toolkit2.git
fi

cd ~/rknn-toolkit2/rknn-toolkit-lite2/packages/

# Install Python 3.10 wheel
WHL=$(ls rknn_toolkit_lite2-*-cp310-cp310-*.whl 2>/dev/null | head -1)
if [ -z "$WHL" ]; then
    error "Could not find RKNN Lite wheel for Python 3.10"
fi

pip3 install "$WHL"

# Verify installation
python3 -c "from rknnlite.api import RKNNLite; print('RKNN Lite OK')" || error "RKNN Lite installation failed"
ok "RKNN Lite runtime installed"

# Update system RKNN runtime library
log "Updating RKNN runtime library..."
sudo cp ~/rknn-toolkit2/rknpu2/runtime/Linux/librknn_api/aarch64/librknnrt.so /usr/lib/
sudo ldconfig
ok "RKNN runtime library updated to v2.3.2"

# =============================================================================
# PHASE 6 — DOWNLOAD MODEL ZOO & RKNN MODEL
# =============================================================================
log "PHASE 6: Setting up model zoo and pose model..."

cd ~
if [ ! -d "rknn_model_zoo" ]; then
    git clone https://github.com/airockchip/rknn_model_zoo.git
fi
ok "RKNN model zoo cloned"

mkdir -p ~/models

# Check if model already exists
if [ ! -f ~/models/yolov8n-pose.rknn ]; then
    warn "yolov8n-pose.rknn not found in ~/models/"
    warn "You need to convert and SCP the model from your Mac:"
    warn ""
    warn "  1. On Mac, use Google Colab (colab.research.google.com):"
    warn "     !pip install rknn-toolkit2 onnx==1.16.1"
    warn "     !curl -L -o yolov8n-pose.onnx 'https://ftrg.zbox.filez.com/v2/delivery/data/95f00b0fc900458ba134f8b180b3f7a1/examples/yolov8_pose/yolov8n-pose.onnx'"
    warn "     from rknn.api import RKNN"
    warn "     rknn = RKNN()"
    warn "     rknn.config(target_platform='rk3588')"
    warn "     rknn.load_onnx(model='yolov8n-pose.onnx')"
    warn "     rknn.build(do_quantization=False)"
    warn "     rknn.export_rknn('yolov8n-pose.rknn')"
    warn "     rknn.release()"
    warn ""
    warn "  2. Download yolov8n-pose.rknn from Colab"
    warn "  3. SCP to Pi: scp ~/Downloads/yolov8n-pose.rknn orangepi@<PI_IP>:~/models/"
    warn ""
    warn "Re-run this script after copying the model, or continue manually."
else
    ok "Model found: ~/models/yolov8n-pose.rknn"
fi

# =============================================================================
# PHASE 7 — PATCH YOLOV8_POSE.PY FOR RKNNLITE
# =============================================================================
log "PHASE 7: Patching yolov8_pose.py for RKNNLite..."

POSE_SCRIPT=~/rknn_model_zoo/examples/yolov8_pose/python/yolov8_pose.py

# Swap import
sed -i 's/from rknn.api import RKNN/from rknnlite.api import RKNNLite as RKNN/' "$POSE_SCRIPT"

# Fix init_runtime call
sed -i 's/ret = rknn.init_runtime(target=args.target, device_id=args.device_id)/ret = rknn.init_runtime(core_mask=RKNN.NPU_CORE_0)/' "$POSE_SCRIPT"

# Fix input dimensions
sed -i 's/infer_img = letterbox_img\[\.\.\..*::-1\]/infer_img = letterbox_img[..., ::-1]\n    infer_img = np.expand_dims(infer_img, axis=0)/' "$POSE_SCRIPT"

ok "yolov8_pose.py patched for RKNNLite"

# =============================================================================
# PHASE 8 — CREATE LIVE PIPELINE SCRIPT
# =============================================================================
log "PHASE 8: Creating Tezeract live pipeline..."

cat > ~/tezeract_live.py << 'PIPELINE'
#!/usr/bin/env python3
"""
TEZERACT — Live Pose Tracking Pipeline v1.0
Camera -> NPU Inference -> Skeleton Overlay -> HDMI Display
"""
import cv2
import numpy as np
import sys
import time
sys.path.append('/home/orangepi/rknn_model_zoo/examples/yolov8_pose/python')

import yolov8_pose as yp
yp.objectThresh = 0.75   # Confidence threshold — raise to reduce false detections
yp.nmsThresh = 0.45

from yolov8_pose import letterbox_resize, process, NMS
from rknnlite.api import RKNNLite as RKNN

MODEL_SIZE = 640
SKELETON = [
    (0,1),(0,2),(1,3),(2,4),          # Head
    (5,6),(5,7),(7,9),(6,8),(8,10),   # Arms
    (5,11),(6,12),(11,12),            # Torso
    (11,13),(13,15),(12,14),(14,16)   # Legs
]

print("[TEZERACT] Loading model on NPU...")
rknn = RKNN()
rknn.load_rknn('/home/orangepi/models/yolov8n-pose.rknn')
rknn.init_runtime(core_mask=RKNN.NPU_CORE_0)
print("[TEZERACT] Model loaded.")

print("[TEZERACT] Opening camera...")
cap = cv2.VideoCapture(0, cv2.CAP_V4L2)
cap.set(cv2.CAP_PROP_FRAME_WIDTH, 1280)
cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 720)
cap.set(cv2.CAP_PROP_FPS, 120)
cap.set(cv2.CAP_PROP_FOURCC, cv2.VideoWriter_fourcc(*'MJPG'))
cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
print(f"[TEZERACT] Camera ready @ {cap.get(cv2.CAP_PROP_FPS):.0f}fps")

cv2.namedWindow('Tezeract', cv2.WINDOW_NORMAL)
cv2.setWindowProperty('Tezeract', cv2.WND_PROP_FULLSCREEN, cv2.WINDOW_FULLSCREEN)

frame_times = []
print("[TEZERACT] Pipeline running. Press Q to quit.")

while True:
    t0 = time.perf_counter()

    # Flush buffer to reduce latency
    for _ in range(3):
        cap.grab()
    ret, frame = cap.retrieve()
    if not ret:
        continue

    frame = cv2.flip(frame, 1)
    h, w = frame.shape[:2]

    # Preprocess
    img, aspect_ratio, offset_x, offset_y = letterbox_resize(
        frame, (MODEL_SIZE, MODEL_SIZE), (128,128,128))
    img = img.astype(np.uint8)
    img_rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
    img_input = np.expand_dims(img_rgb, axis=0)

    # NPU Inference
    outputs = rknn.inference(inputs=[img_input])

    # Parse & Draw
    if outputs is not None:
        out0 = outputs[0].reshape(1, 65, -1)
        out1 = outputs[1].reshape(1, 65, -1)
        out2 = outputs[2].reshape(1, 65, -1)
        kpts = outputs[3]

        results = NMS(
            process(out0, kpts, 0, 80, 80, 8,  1.0) +
            process(out1, kpts, 1, 40, 40, 16, 1.0) +
            process(out2, kpts, 2, 20, 20, 32, 1.0)
        )

        for det in results:
            kp = det.keypoint[0]  # (17, 3)
            pts = []
            for i in range(17):
                x = int(kp[i][0] * w / MODEL_SIZE)
                y = int(kp[i][1] * h / MODEL_SIZE)
                c = float(kp[i][2])
                pts.append((x, y, c))
                if c > 0.5:
                    cv2.circle(frame, (x, y), 6, (0, 255, 0), -1)
            for a, b in SKELETON:
                if pts[a][2] > 0.5 and pts[b][2] > 0.5:
                    cv2.line(frame,
                        (pts[a][0], pts[a][1]),
                        (pts[b][0], pts[b][1]),
                        (147, 51, 234), 2)

    # FPS overlay
    latency = (time.perf_counter() - t0) * 1000
    frame_times.append(latency)
    if len(frame_times) > 30:
        frame_times.pop(0)
    avg = sum(frame_times) / len(frame_times)
    fps = 1000 / avg if avg > 0 else 0
    cv2.putText(frame, f'{avg:.1f}ms | {fps:.0f}fps',
        (10, 30), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (147, 51, 234), 2)

    cv2.imshow('Tezeract', frame)
    if cv2.waitKey(1) & 0xFF == ord('q'):
        break

cap.release()
cv2.destroyAllWindows()
rknn.release()
print("[TEZERACT] Pipeline stopped.")
PIPELINE

chmod +x ~/tezeract_live.py
ok "tezeract_live.py created"

# =============================================================================
# PHASE 9 — SET DISPLAY & AUTOSTART (OPTIONAL)
# =============================================================================
log "PHASE 9: Setting up display environment..."

# Add DISPLAY export to .bashrc so it persists
grep -q "export DISPLAY=:0.0" ~/.bashrc || echo "export DISPLAY=:0.0" >> ~/.bashrc
export DISPLAY=:0.0
ok "DISPLAY variable set"

# =============================================================================
# DONE
# =============================================================================
echo ""
echo -e "${PURPLE}=================================================${NC}"
echo -e "${GREEN}  TEZERACT BOOTSTRAP COMPLETE!${NC}"
echo -e "${PURPLE}=================================================${NC}"
echo ""
echo "  Next steps:"
echo ""
echo "  1. Make sure yolov8n-pose.rknn is in ~/models/"
echo "     (See Phase 6 instructions above if not done)"
echo ""
echo "  2. Run the live pipeline:"
echo "     python3 ~/tezeract_live.py"
echo ""
echo "  3. Kill from Mac if needed:"
echo "     ssh orangepi@<IP> 'pkill -f tezeract_live.py'"
echo ""
echo "  4. Change your password:"
echo "     passwd"
echo ""
echo -e "${PURPLE}  Let's build something amazing. 🚀${NC}"
echo ""
