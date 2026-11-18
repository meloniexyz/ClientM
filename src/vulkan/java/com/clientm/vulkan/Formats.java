package com.clientm.vulkan;

import static org.lwjgl.vulkan.VK10.*;

import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

/** OpenGL texture formats to Vulkan formats, and how OpenGL pixel data is laid out. */
@Lwjgl3Aware
final class Formats {

    private Formats() {}

    // OpenGL internal formats
    static final int GL_RGBA = 0x1908, GL_RGB = 0x1907, GL_RGBA8 = 0x8058, GL_RGB8 = 0x8051, GL_ALPHA = 0x1906,
        GL_LUMINANCE = 0x1909, GL_RED = 0x1903, GL_R8 = 0x8229, GL_RG = 0x8227, GL_RG8 = 0x822B, GL_RGBA16F = 0x881A,
        GL_RGB16F = 0x881B, GL_RGBA32F = 0x8814, GL_R32F = 0x822E, GL_R16F = 0x822D, GL_RG16F = 0x822F,
        GL_RG32F = 0x8230, GL_RGB10_A2 = 0x8059, GL_R11F_G11F_B10F = 0x8C3A, GL_DEPTH_COMPONENT = 0x1902,
        GL_DEPTH_COMPONENT16 = 0x81A5, GL_DEPTH_COMPONENT24 = 0x81A6, GL_DEPTH_COMPONENT32 = 0x81A7,
        GL_DEPTH_COMPONENT32F = 0x8CAC, GL_DEPTH_STENCIL = 0x84F9, GL_DEPTH24_STENCIL8 = 0x88F0,
        GL_DEPTH32F_STENCIL8 = 0x8CAD, GL_BGRA = 0x80E1, GL_SRGB8_ALPHA8 = 0x8C43, GL_RGBA16 = 0x805B, GL_R16 = 0x822A,
        GL_RG16 = 0x822C;

    // OpenGL pixel types
    static final int GL_UNSIGNED_BYTE = 0x1401, GL_FLOAT = 0x1406, GL_HALF_FLOAT = 0x140B,
        GL_UNSIGNED_INT_8_8_8_8 = 0x8035, GL_UNSIGNED_INT_8_8_8_8_REV = 0x8367, GL_UNSIGNED_INT = 0x1405,
        GL_UNSIGNED_INT_24_8 = 0x84FA, GL_UNSIGNED_SHORT = 0x1403;

    static boolean isDepth(int internal) {
        switch (internal) {
            case GL_DEPTH_COMPONENT:
            case GL_DEPTH_COMPONENT16:
            case GL_DEPTH_COMPONENT24:
            case GL_DEPTH_COMPONENT32:
            case GL_DEPTH_COMPONENT32F:
            case GL_DEPTH_STENCIL:
            case GL_DEPTH24_STENCIL8:
            case GL_DEPTH32F_STENCIL8:
                return true;
            default:
                return false;
        }
    }

    /** The Vulkan format for an OpenGL internal format; colour formats default to 8-bit RGBA. */
    static int vulkan(int internal, int depthFormat) {
        if (isDepth(internal)) {
            return internal == GL_DEPTH_COMPONENT32F || internal == GL_DEPTH32F_STENCIL8 ? VK_FORMAT_D32_SFLOAT_S8_UINT
                : depthFormat;
        }
        switch (internal) {
            case GL_RGBA16F:
            case GL_RGB16F:
                return VK_FORMAT_R16G16B16A16_SFLOAT;
            case GL_RGBA32F:
                return VK_FORMAT_R32G32B32A32_SFLOAT;
            case GL_R32F:
                return VK_FORMAT_R32_SFLOAT;
            case GL_R16F:
                return VK_FORMAT_R16_SFLOAT;
            case GL_RG16F:
                return VK_FORMAT_R16G16_SFLOAT;
            case GL_RG32F:
                return VK_FORMAT_R32G32_SFLOAT;
            case GL_RGB10_A2:
                return VK_FORMAT_A2B10G10R10_UNORM_PACK32;
            case GL_R11F_G11F_B10F:
                return VK_FORMAT_B10G11R11_UFLOAT_PACK32;
            case GL_RED:
            case GL_R8:
                return VK_FORMAT_R8_UNORM;
            case GL_RG:
            case GL_RG8:
                return VK_FORMAT_R8G8_UNORM;
            case GL_RGBA16:
                return VK_FORMAT_R16G16B16A16_UNORM;
            case GL_SRGB8_ALPHA8:
                return VK_FORMAT_R8G8B8A8_SRGB;
            default:
                return VK_FORMAT_R8G8B8A8_UNORM;
        }
    }

    static boolean hasStencil(int vkFormat) {
        return vkFormat == VK_FORMAT_D24_UNORM_S8_UINT || vkFormat == VK_FORMAT_D32_SFLOAT_S8_UINT;
    }

    /** Bytes per pixel of a Vulkan colour format. */
    static int texelSize(int vkFormat) {
        switch (vkFormat) {
            case VK_FORMAT_R8_UNORM:
                return 1;
            case VK_FORMAT_R8G8_UNORM:
            case VK_FORMAT_R16_SFLOAT:
                return 2;
            case VK_FORMAT_R16G16B16A16_SFLOAT:
            case VK_FORMAT_R32G32_SFLOAT:
            case VK_FORMAT_R16G16B16A16_UNORM:
                return 8;
            case VK_FORMAT_R32G32B32A32_SFLOAT:
                return 16;
            default:
                return 4;
        }
    }
}
