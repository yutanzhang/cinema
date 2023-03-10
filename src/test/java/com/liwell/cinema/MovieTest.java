package com.liwell.cinema;

import com.liwell.cinema.domain.dto.MvCollectDTO;
import com.liwell.cinema.service.MovieService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Description:
 *
 * @author Li
 * @date Created on 2023/1/30
 */
@SpringBootTest
@ActiveProfiles("local")
public class MovieTest {

    @Autowired
    private MovieService movieService;

    @Test
    public void collect() {
        MvCollectDTO mvCollectDTO = new MvCollectDTO();
        mvCollectDTO.setSourceId(1);
    }

}
