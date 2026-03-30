package com.hotvect.python.direct;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CudaDeviceUtilsTest {
    @Test
    void parseCudaVisibleDevicesAllowlist_handlesNullBlankAndSentinel() {
        assertEquals(List.of(), CudaDeviceUtils.parseCudaVisibleDevicesAllowlist(null));
        assertEquals(List.of(), CudaDeviceUtils.parseCudaVisibleDevicesAllowlist(""));
        assertEquals(List.of(), CudaDeviceUtils.parseCudaVisibleDevicesAllowlist("   "));
        assertEquals(List.of(), CudaDeviceUtils.parseCudaVisibleDevicesAllowlist("-1"));
        assertEquals(List.of(), CudaDeviceUtils.parseCudaVisibleDevicesAllowlist("NoDevFiles"));

        assertEquals(List.of("2", "3"), CudaDeviceUtils.parseCudaVisibleDevicesAllowlist("2,3"));
        assertEquals(List.of("2", "3"), CudaDeviceUtils.parseCudaVisibleDevicesAllowlist(" 2 , 3 "));
    }

    @Test
    void parseCudaVisibleDevicesAllowlist_rejectsDuplicates() {
        assertThrows(IllegalArgumentException.class, () -> CudaDeviceUtils.parseCudaVisibleDevicesAllowlist("2,2"));
    }

    @Test
    void parseNvidiaSmiGpuList_extractsUniqueDeviceIds() {
        String output = """
                Some header
                GPU 0: NVIDIA A10G (UUID: GPU-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee)
                GPU 1: NVIDIA A10G (UUID: GPU-ffffffff-1111-2222-3333-444444444444)
                GPU 1: NVIDIA A10G (UUID: GPU-duplicate-ignored)
                GPU X: invalid
                """;

        assertEquals(List.of(0, 1), CudaDeviceUtils.parseNvidiaSmiGpuList(output));
    }

    @Test
    void selectCudaVisibleDeviceTokens_numberMeansCount_listMeansIndices() {
        List<String> available = List.of("2", "3", "7");

        assertEquals(List.of("2", "3"), CudaDeviceUtils.selectCudaVisibleDeviceTokens(2, available));
        assertEquals(List.of("2", "7"), CudaDeviceUtils.selectCudaVisibleDeviceTokens(List.of(0, 2), available));
    }
}
