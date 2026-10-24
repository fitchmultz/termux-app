     .global blob
     .global blob_size
     .section .rodata
 blob:
 #if !defined __aarch64__
 # error The Fold bootstrap supports ARM64 only
 #endif
 #ifndef FOLD_BOOTSTRAP_ARCHIVE
 # error A verified build-private bootstrap path is required
 #endif
     .incbin FOLD_BOOTSTRAP_ARCHIVE
 1:
 blob_size:
     .int 1b - blob
