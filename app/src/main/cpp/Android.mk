LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := libtermux-bootstrap
LOCAL_SRC_FILES := termux-bootstrap-zip.S termux-bootstrap.c
ifndef FOLD_BOOTSTRAP_DIR
$(error FOLD_BOOTSTRAP_DIR must point to the verified Gradle build-private bootstrap)
endif
LOCAL_ASFLAGS += '-DFOLD_BOOTSTRAP_ARCHIVE="$(FOLD_BOOTSTRAP_DIR)/bootstrap-aarch64.zip"'
include $(BUILD_SHARED_LIBRARY)
