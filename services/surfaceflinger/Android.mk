LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
    Client.cpp                               \
    DisplayDevice.cpp                        \
    EventThread.cpp                          \
    Layer.cpp 				     \
    LayerBase.cpp 			     \
    LayerDim.cpp 			     \
    LayerScreenshot.cpp	                     \
    DdmConnection.cpp                        \
    DisplayHardware/FramebufferSurface.cpp   \
    DisplayHardware/GraphicBufferAlloc.cpp   \
    DisplayHardware/HWComposer.cpp 	     \
    DisplayHardware/PowerHAL.cpp             \
    GLExtensions.cpp 			     \
    MessageQueue.cpp 		             \
    SurfaceFlinger.cpp 			     \
    SurfaceTextureLayer.cpp 		     \
    Transform.cpp 			     \
    

LOCAL_CFLAGS:= -DLOG_TAG=\"SurfaceFlinger\"
LOCAL_CFLAGS += -DGL_GLEXT_PROTOTYPES -DEGL_EGLEXT_PROTOTYPES

ifeq ($(TARGET_BOARD_PLATFORM), omap3)
	LOCAL_CFLAGS += -DNO_RGBX_8888
endif
ifeq ($(TARGET_BOARD_PLATFORM), omap4)
	LOCAL_CFLAGS += -DHAS_CONTEXT_PRIORITY
endif
ifeq ($(TARGET_BOARD_PLATFORM), s5pc110)
	LOCAL_CFLAGS += -DHAS_CONTEXT_PRIORITY -DNEVER_DEFAULT_TO_ASYNC_MODE
	LOCAL_CFLAGS += -DREFRESH_RATE=56
endif

ifeq ($(TARGET_DISABLE_TRIPLE_BUFFERING),true)
        LOCAL_CFLAGS += -DTARGET_DISABLE_TRIPLE_BUFFERING
endif

ifeq ($(BOARD_EGL_NEEDS_LEGACY_FB),true)
        LOCAL_CFLAGS += -DBOARD_EGL_NEEDS_LEGACY_FB
endif

ifneq ($(NUM_FRAMEBUFFER_SURFACE_BUFFERS),)
  LOCAL_CFLAGS += -DNUM_FRAMEBUFFER_SURFACE_BUFFERS=$(NUM_FRAMEBUFFER_SURFACE_BUFFERS)
endif

ifeq ($(BOARD_USES_QCOM_HARDWARE),true)
LOCAL_SHARED_LIBRARIES := \
	libQcomUI
LOCAL_C_INCLUDES += hardware/qcom/display/libqcomui 
          
LOCAL_CFLAGS += -DQCOM_HARDWARE
endif

LOCAL_SHARED_LIBRARIES := \
	libcutils         \
	libhardware       \
        libdl             \
	libutils          \
	libEGL            \
	libGLESv1_CM      \
	libbinder         \
	libui             \
	libgui

# this is only needed for DDMS debugging
LOCAL_SHARED_LIBRARIES += libdvm libandroid_runtime

ifeq ($(TARGET_HAVE_BYPASS),true)
    LOCAL_CFLAGS += -DBUFFER_COUNT_SERVER=3
else
    LOCAL_CFLAGS += -DBUFFER_COUNT_SERVER=2
endif

LOCAL_C_INCLUDES := \
	$(call include-path-for, corecg graphics)

LOCAL_C_INCLUDES += hardware/libhardware/modules/gralloc

ifeq ($(BOARD_USES_QCOM_HARDWARE),true)
LOCAL_SHARED_LIBRARIES += \
	libQcomUI
LOCAL_C_INCLUDES += hardware/qcom/display/libqcomui
LOCAL_CFLAGS += -DQCOM_HARDWARE
ifeq ($(TARGET_QCOM_HDMI_OUT),true)
LOCAL_CFLAGS += -DQCOM_HDMI_OUT
endif
endif

LOCAL_MODULE:= libsurfaceflinger

include $(BUILD_SHARED_LIBRARY)
