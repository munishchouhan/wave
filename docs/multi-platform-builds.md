# Multi-Platform Container Image Build Support

Wave now supports building container images for multiple architectures simultaneously, creating multi-arch images that can run on different platforms.

## Overview

Multi-platform builds allow you to create container images that support multiple CPU architectures (e.g., `linux/amd64`, `linux/arm64`) in a single build request. This creates a manifest list (also known as a multi-arch image) that contains references to platform-specific images.

## Usage

### Single Platform (Existing Behavior)

```json
{
  "containerFile": "RlJPTSB1YnVudHU6bGF0ZXN0",
  "containerPlatform": "linux/amd64"
}
```

### Multi-Platform (New Feature)

To build for multiple platforms, specify a comma-separated list of platforms:

```json
{
  "containerFile": "RlJPTSB1YnVudHU6bGF0ZXN0",
  "containerPlatform": "linux/amd64,linux/arm64"
}
```

### Supported Platforms

- `linux/amd64` (x86_64, x86-64)
- `linux/arm64` (aarch64)
- `linux/arm` (with variants v5, v6, v7)

### Examples

#### Basic Multi-Platform Build
```json
{
  "containerFile": "RlJPTSB1YnVudHU6bGF0ZXN0",
  "containerPlatform": "linux/amd64,linux/arm64",
  "buildRepository": "your-registry.com/your-repo"
}
```

#### Multi-Platform with Build Context
```json
{
  "containerFile": "RlJPTSBub2RlOjE4LWFscGluZQpSVU4gYXBrIGFkZCAtLW5vLWNhY2hlIGdpdA==",
  "containerPlatform": "linux/amd64,linux/arm64,linux/arm/v7",
  "buildContext": {
    "file1.txt": "content"
  },
  "buildRepository": "your-registry.com/your-repo"
}
```

## Implementation Details

### Build Process

1. **Single Platform Detection**: If only one platform is specified, the build uses the existing single-platform pipeline for backward compatibility.

2. **Multi-Platform Detection**: When multiple platforms are detected (comma-separated), Wave:
   - Creates a `MultiPlatformBuildRequest` wrapper
   - Uses buildkit's native multi-platform support
   - Generates build commands with `--opt platform=linux/amd64,linux/arm64`
   - Creates manifest lists automatically

### Buildkit Integration

Multi-platform builds leverage buildkit's native capabilities:

```bash
buildctl build \
  --frontend dockerfile.v0 \
  --opt platform=linux/amd64,linux/arm64 \
  --output type=image,name=registry.com/image:tag,push=true
```

### Node Selection (Kubernetes)

For Kubernetes deployments, Wave can use different node selectors for different platforms:

```yaml
wave:
  build:
    k8s:
      node-selector:
        "linux/amd64": "service=wave-build"
        "linux/arm64": "service=wave-build-arm64"
```

## Container Augmentation

Multi-platform images work seamlessly with Wave's container augmentation features:

- **Layer Injection**: Layers can be injected into multi-arch images
- **Configuration Changes**: Environment variables, working directories, etc. are applied to all platforms
- **Platform-Specific Handling**: Wave automatically handles platform-specific manifests within manifest lists

## Backward Compatibility

The multi-platform feature is fully backward compatible:

- Existing single-platform requests continue to work unchanged
- Single-platform requests with multi-platform syntax (`"linux/amd64"`) work as before
- No breaking changes to existing APIs or functionality

## Limitations

1. **Kubernetes Strategy Required**: Full multi-platform support requires the Kubernetes build strategy. Other strategies fall back to single-platform builds.

2. **Platform Support**: Limited to platforms supported by the underlying buildkit image and Kubernetes nodes.

3. **Build Time**: Multi-platform builds may take longer as they need to build for multiple architectures.

## Error Handling

- **Invalid Platform**: Returns a clear error message for unsupported platforms
- **Missing Architecture**: If a platform cannot be built, the build fails with detailed logs
- **Resource Constraints**: Respects rate limiting and resource constraints across all platforms

## Migration Guide

To migrate existing single-platform builds to multi-platform:

1. **Update containerPlatform**: Change from single platform to comma-separated list
2. **Verify Registry Support**: Ensure your target registry supports manifest lists
3. **Test Platform Compatibility**: Verify your application works on all target platforms
4. **Update CI/CD**: Modify build pipelines to handle longer build times

## Examples of Generated Commands

### Single Platform (Existing)
```bash
buildctl build --opt platform=linux/amd64 --output type=image,name=repo:tag,push=true
```

### Multi-Platform (New)
```bash
buildctl build --opt platform=linux/amd64,linux/arm64 --output type=image,name=repo:tag,push=true
```

The resulting image will be a manifest list containing platform-specific images that can be pulled with `docker pull repo:tag` on any supported platform.