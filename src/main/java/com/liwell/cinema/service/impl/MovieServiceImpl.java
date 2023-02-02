package com.liwell.cinema.service.impl;

import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.liwell.cinema.domain.dto.IdDTO;
import com.liwell.cinema.domain.dto.MvCollectDTO;
import com.liwell.cinema.domain.dto.MvPageDTO;
import com.liwell.cinema.domain.entity.CategoryMapping;
import com.liwell.cinema.domain.entity.Movie;
import com.liwell.cinema.domain.entity.Playlist;
import com.liwell.cinema.domain.entity.SourceConfig;
import com.liwell.cinema.domain.enums.MvAreaEnum;
import com.liwell.cinema.domain.enums.ResultEnum;
import com.liwell.cinema.domain.enums.StateEnum;
import com.liwell.cinema.domain.po.CollectDetail;
import com.liwell.cinema.domain.po.CollectDetailResult;
import com.liwell.cinema.domain.po.CollectListResult;
import com.liwell.cinema.domain.vo.MovieDetailVO;
import com.liwell.cinema.domain.vo.MvPageVO;
import com.liwell.cinema.exception.ResultException;
import com.liwell.cinema.mapper.CategoryMappingMapper;
import com.liwell.cinema.mapper.MovieMapper;
import com.liwell.cinema.mapper.PlaylistMapper;
import com.liwell.cinema.remote.SourceService;
import com.liwell.cinema.service.MovieService;
import com.liwell.cinema.util.EnumUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Description:
 *
 * @author Li
 * @date Created on 2023/1/24
 */
@Service
@Slf4j
public class MovieServiceImpl extends ServiceImpl<MovieMapper, Movie> implements MovieService {

    private final String MOVIE_ID = "movie_id";

    @Autowired
    private PlaylistMapper playlistMapper;
    @Autowired
    private CategoryMappingMapper categoryMappingMapper;
    @Autowired
    private SourceService sourceService;
    @Autowired
    private RestTemplate restTemplate;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * 资源采集
     * @param mvCollectDTO
     */
    @Override
    @Transactional
    public void collect(MvCollectDTO mvCollectDTO) {
        SourceConfig sourceConfig = sourceService.getSourceConfig(mvCollectDTO.getSourceId());
        CollectListResult baseInfoResult = sourceService.sourceBaseInfo(sourceConfig.getId());
        Integer pageCount = baseInfoResult.getPagecount();
        Map<Integer, Integer> categoryMapping = getCategoryMapping(sourceConfig.getId());
        for (int i = 1; i <= pageCount; i++) {
            CollectDetailResult detailResult = sourceService.pageSource(sourceConfig.getDetailUrl(), i);
            if (detailResult == null) {
                continue;
            }
            List<CollectDetail> collectDetails = detailResult.getList();
            List<Movie> movies = new ArrayList<>();
            List<Playlist> playlists = new ArrayList<>();
            for (CollectDetail collectDetail : collectDetails) {
                Movie movie = generateMovie(collectDetail, categoryMapping);
                if (movie == null) {
                    log.info("影片id：" + collectDetail.getVod_id() + "，片名：《" + collectDetail.getVod_name() + "》，" +
                            "类型：" + collectDetail.getType_id() + "-" + collectDetail.getType_name() + "，未匹配到对应类型，采集失败，跳过");
                    continue;
                }
                movies.add(movie);
                playlists.add(generatePlaylist(collectDetail, movie.getId(), mvCollectDTO));
                log.info("影片id：" + collectDetail.getVod_id() + "，片名：《" + collectDetail.getVod_name() + "》，" +
                        "类型：" + collectDetail.getType_id() + "-" + collectDetail.getType_name() + "，采集成功！");
            }
            if (movies.size() == 0) {
                continue;
            }
            baseMapper.insertMovies(movies);
            playlistMapper.insertPlaylist(playlists);
        }
    }

    /**
     * 获取当前采集源的分类映射关系
     * @param collectId
     * @return
     */
    private Map<Integer, Integer> getCategoryMapping(Integer collectId) {
        List<CategoryMapping> categoryMappingList = categoryMappingMapper.selectList(new QueryWrapper<CategoryMapping>().eq("source_id", collectId));
        Map<Integer, Integer> result = new HashMap<>();
        for (CategoryMapping categoryMapping : categoryMappingList) {
            result.put(categoryMapping.getSourceTypeId(), categoryMapping.getCategoryId());
        }
        return result;
    }

    /**
     * 生成movie
     * @param collectDetail
     * @param categoryMapping
     * @return
     */
    private Movie generateMovie(CollectDetail collectDetail, Map<Integer, Integer> categoryMapping) {
        Movie movie = new Movie();
        movie.setMvName(collectDetail.getVod_name());
        if (categoryMapping.get(collectDetail.getType_id()) == null) {
            return null;
        }
        movie.setMvType(categoryMapping.get(collectDetail.getType_id()));
        movie.setMvArea(EnumUtils.get(MvAreaEnum.class, collectDetail.getVod_area()) == null ?
                MvAreaEnum.UNKNOWN : EnumUtils.get(MvAreaEnum.class, collectDetail.getVod_area()));
        movie.setMvYear(extractNumber(collectDetail.getVod_year()));
        movie.setCreateTime(DateUtil.parse(collectDetail.getVod_year(), DatePattern.NORM_YEAR_PATTERN));
        movie.setUpdateTime(new Date());
        movie.setUpdateInfo(extractNumber(collectDetail.getVod_remarks()));
        movie.setDescription(collectDetail.getVod_content());
        movie.setActorList(collectDetail.getVod_actor());
        movie.setDirectorList(collectDetail.getVod_director());
        movie.setState(StateEnum.VALID);
        movie.setPicture(collectDetail.getVod_pic());
        movie.setScore(Double.valueOf(collectDetail.getVod_douban_score()));
        int movieId = generateMovieId();
        movie.setId(movieId);
        return movie;
    }

    private Integer extractNumber(String target) {
        String REGEX = "[^0-9]";
        String result = Pattern.compile(REGEX).matcher(target).replaceAll("").trim();
        if (StringUtils.isNotBlank(result)) {
            return Integer.parseInt(result);
        }
        return null;
    }

    /**
     * 生成 movieId
     * @return
     */
    private int generateMovieId() {
        ValueOperations<String, Object> valueOperations = redisTemplate.opsForValue();
        Long movieId = valueOperations.increment(MOVIE_ID);
        return movieId.intValue();
    }

    /**
     * 生成播放列表
     * @param collectDetail
     * @param movieId
     * @param dto
     * @return
     */
    private Playlist generatePlaylist(CollectDetail collectDetail, Integer movieId, MvCollectDTO dto) {
        Playlist playlist = new Playlist();
        playlist.setMovieId(movieId);
        playlist.setSourceId(dto.getSourceId());
        playlist.setSourceMovieId(collectDetail.getVod_id());
        playlist.setPlayType(collectDetail.getVod_play_from());
        playlist.setPlayUrl(collectDetail.getVod_play_url());
        playlist.setSeparatorNote(collectDetail.getVod_play_note());
        playlist.setUpdateTime(DateUtil.parse(collectDetail.getVod_time(), DatePattern.NORM_DATETIME_PATTERN));
        return playlist;
    }

    /**
     * 分页列表
     * @param mvPageDTO
     * @return
     */
    @Override
    public Page<MvPageVO> pageMovie(MvPageDTO mvPageDTO) {
        return baseMapper.pageMovie(mvPageDTO);
    }

    /**
     * 获取影片详情
     * @param dto
     * @return
     */
    @Override
    public MovieDetailVO getMovieDetail(IdDTO dto) {
        MovieDetailVO detailVO = baseMapper.getMovieDetail(dto);
        if (detailVO == null) {
            throw new ResultException(ResultEnum.DATA_NOT_EXIST);
        }
        return detailVO;
    }
}
