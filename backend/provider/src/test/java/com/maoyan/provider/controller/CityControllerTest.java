package com.maoyan.provider.controller;

import com.maoyan.domain.model.vo.CityVO;
import com.maoyan.service.CityService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CityControllerTest {

    @Test
    void canonicalAndLegacyEndpointsShareTheSameContract() throws Exception {
        CityService cityService = mock(CityService.class);
        CityVO city = new CityVO();
        city.setId(1L);
        city.setNm("北京");
        when(cityService.getAllCities()).thenReturn(List.of(city));

        CityController controller = new CityController();
        ReflectionTestUtils.setField(controller, "cityService", cityService);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/api/cities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cts[0].nm").value("北京"));
        mockMvc.perform(get("/dianying/cities.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cts[0].nm").value("北京"));
    }
}
