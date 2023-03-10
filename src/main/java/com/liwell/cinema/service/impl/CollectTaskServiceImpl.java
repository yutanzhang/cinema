package com.liwell.cinema.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.thread.ThreadUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.liwell.cinema.domain.dto.CollectTaskAddDTO;
import com.liwell.cinema.domain.dto.CollectTaskPageDTO;
import com.liwell.cinema.domain.entity.CollectTask;
import com.liwell.cinema.domain.entity.Movie;
import com.liwell.cinema.domain.entity.Playlist;
import com.liwell.cinema.domain.entity.SourceConfig;
import com.liwell.cinema.domain.enums.CollectTaskStateEnum;
import com.liwell.cinema.domain.enums.ResultEnum;
import com.liwell.cinema.domain.po.CollectDetail;
import com.liwell.cinema.domain.po.CollectDetailResult;
import com.liwell.cinema.domain.po.CollectListResult;
import com.liwell.cinema.domain.vo.CollectTaskPageVO;
import com.liwell.cinema.exception.ResultException;
import com.liwell.cinema.helper.RedisHelper;
import com.liwell.cinema.mapper.CollectTaskMapper;
import com.liwell.cinema.mapper.MovieMapper;
import com.liwell.cinema.mapper.PlaylistMapper;
import com.liwell.cinema.remote.SourceService;
import com.liwell.cinema.service.CategoryMappingService;
import com.liwell.cinema.service.CollectTaskService;
import com.liwell.cinema.service.MovieService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Description:
 *
 * @author: litianyi
 * @date: Created on 2023/02/03
 */
@Slf4j
@Service
public class CollectTaskServiceImpl extends ServiceImpl<CollectTaskMapper, CollectTask> implements CollectTaskService {

    private static final String COLLECT_STATE_KEY = "collect_state_key:";
    private static final String COLLECT_PROCESS_KEY = "collect_process_key:";

    @Autowired
    private MovieMapper movieMapper;
    @Autowired
    private PlaylistMapper playlistMapper;
    @Autowired
    private CollectTaskMapper collectTaskMapper;
    @Autowired
    private CategoryMappingService categoryMappingService;
    @Autowired
    private SourceService sourceService;
    @Autowired
    private MovieService movieService;
    @Autowired
    private TaskExecutor taskExecutor;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    @Autowired
    private RedisHelper redisHelper;

    @Override
    @Transactional
    public Boolean addCollectTask(CollectTaskAddDTO dto) {
        if (CollectionUtil.isEmpty(dto.getSourceIds())) {
            throw new ResultException(ResultEnum.PARAMETER_ERROR);
        }
        List<CollectTask> collectTasks = baseMapper.selectList(
                new QueryWrapper<CollectTask>().in("source_id", dto.getSourceIds())
                        .notIn("state", CollectTaskStateEnum.STOP, CollectTaskStateEnum.FINISHED));
        if (CollectionUtil.isNotEmpty(collectTasks)) {
            throw new ResultException(ResultEnum.TASK_IN_EXECUTE);
        }
        List<CollectTask> collectTaskList = new ArrayList<>();
        for (Integer sourceId : dto.getSourceIds()) {
            CollectTask collectTask = new CollectTask();
            collectTask.init(sourceId, dto.getDuration());
            collectTaskList.add(collectTask);
        }
        baseMapper.addCollectTasks(collectTaskList);
        return true;
    }

    @PostConstruct
    public void postConstruct() {
        taskExecutor.execute(new TaskScheduling());
    }

    /**
     * ??????????????????
     */
    public class TaskScheduling implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    List<CollectTask> collectTaskList = baseMapper.selectList(
                            new QueryWrapper<CollectTask>().in("state", 0));
                    for (CollectTask collectTask : collectTaskList) {
                        taskExecutor.execute(new CollectThread(collectTask));
                    }
                } catch (Exception e) {
                    log.error("???????????????????????????", e);
                } finally {
                    ThreadUtil.sleep(10000);
                }
            }
        }
    }

    /**
     * ??????????????????
     */
    public class CollectThread implements Runnable {

        private CollectTask collectTask;

        public CollectThread(CollectTask collectTask) {
            this.collectTask = collectTask;
        }

        @Override
        @Transactional
        public void run() {
            collectTaskMapper.update(null, new UpdateWrapper<CollectTask>()
                    .set("state", CollectTaskStateEnum.IN_EXECUTE).eq("id", collectTask.getId()));
            ValueOperations<String, Object> opsForValue = redisTemplate.opsForValue();
            opsForValue.set(COLLECT_STATE_KEY + collectTask.getId(), CollectTaskStateEnum.IN_EXECUTE.getValue());
            SourceConfig sourceConfig = sourceService.getSourceConfig(collectTask.getSourceId());
            CollectListResult baseInfoResult = sourceService.sourceBaseInfo(sourceConfig.getId());
            Integer pageCount = baseInfoResult.getPagecount();
            Map<Integer, Integer> categoryMapping = categoryMappingService.getCategoryMapping(sourceConfig.getId());
            int page = collectTask.getCurrentPage() == null ? 0 : collectTask.getCurrentPage() + 1;
            if (page == 0) {
                collectTaskMapper.update(null, new UpdateWrapper<CollectTask>()
                        .set("start_time", new Date()).eq("id", collectTask.getId()));
            }
            for (; page <= pageCount; page++) {
                try {
                    log.info("?????????????????????" + page + " ???");
                    CollectDetailResult detailResult = sourceService.pageSource(sourceConfig.getDetailUrl(), page);
                    if (detailResult == null) {
                        continue;
                    }
                    List<CollectDetail> collectDetails = detailResult.getList();
                    List<Movie> movies = new ArrayList<>();
                    List<Playlist> playlists = new ArrayList<>();
                    for (CollectDetail collectDetail : collectDetails) {
                        if (categoryMapping.get(collectDetail.getType_id()) == null) {
                            log.info("??????id???" + collectDetail.getVod_id() + "???????????????" +
                                    collectDetail.getVod_name() + "???????????????" + collectDetail.getType_id()
                                    + "-" + collectDetail.getType_name() + "???????????????????????????????????????????????????");
                            continue;
                        }
                        Movie movie = new Movie().init(collectDetail);
                        movie.setId(movieService.generateMovieId());
                        movie.setMvType(categoryMapping.get(collectDetail.getType_id()));
                        movies.add(movie);
                        playlists.add(generatePlaylist(collectDetail, movie.getId(), collectTask.getSourceId()));
                        log.info("??????id???" + collectDetail.getVod_id() + "???????????????" +
                                collectDetail.getVod_name() + "???????????????" + collectDetail.getType_id()
                                + "-" + collectDetail.getType_name() + "??????????????????");
                    }
                    opsForValue.set(COLLECT_PROCESS_KEY + collectTask.getId(), page + ":" + pageCount);
                    if (movies.size() == 0) {
                        continue;
                    }
                    movieMapper.insertMovies(movies);
                    playlistMapper.insertPlaylist(playlists);
                    CollectTaskStateEnum taskState = redisHelper.getCollectTaskState(CollectTaskStateEnum.class, collectTask.getId());
                    if (taskState == CollectTaskStateEnum.PAUSE || taskState == CollectTaskStateEnum.STOP) {
                        log.info("???????????????????????????????????????" + collectTask.getId() + " ?????????");
                        collectTaskMapper.update(null, new UpdateWrapper<CollectTask>()
                                .set("current_page", page).set("total_page", pageCount).eq("id", collectTask.getId()));
                        if (taskState == CollectTaskStateEnum.STOP) {
                            redisTemplate.delete(COLLECT_STATE_KEY + collectTask.getId());
                            redisTemplate.delete(COLLECT_PROCESS_KEY + collectTask.getId());
                        }
                        return;
                    }
                } catch (Exception e) {
                    log.error("?????????" + collectTask.getId() + "???????????????" + page + " ???????????????", e);
                }
            }
            baseMapper.update(null, new UpdateWrapper<CollectTask>()
                    .set("current_page", pageCount).set("total_page", pageCount)
                    .set("state", CollectTaskStateEnum.FINISHED).eq("id", collectTask.getId()));
            redisTemplate.delete(COLLECT_STATE_KEY + collectTask.getId());
            redisTemplate.delete(COLLECT_PROCESS_KEY + collectTask.getId());
            log.info("???????????????" + collectTask.getId() + " ???????????????");
        }
    }

    /**
     * ??????????????????
     *
     * @param collectDetail
     * @param movieId
     * @param sourceId
     * @return
     */
    private Playlist generatePlaylist(CollectDetail collectDetail, Integer movieId, Integer sourceId) {
        Playlist playlist = new Playlist();
        playlist.setMovieId(movieId);
        playlist.setSourceId(sourceId);
        playlist.setSourceMovieId(collectDetail.getVod_id());
        playlist.setPlayType(collectDetail.getVod_play_from());
        playlist.setPlayUrl(collectDetail.getVod_play_url());
        playlist.setSeparatorNote(collectDetail.getVod_play_note());
        playlist.setUpdateTime(DateUtil.parse(collectDetail.getVod_time(), DatePattern.NORM_DATETIME_PATTERN));
        return playlist;
    }

    /**
     * ????????????
     * @param id
     * @return
     */
    @Override
    public Boolean pauseCollectTask(Integer id) {
        ValueOperations<String, Object> opsForValue = redisTemplate.opsForValue();
        CollectTaskStateEnum taskState = redisHelper.getCollectTaskState(CollectTaskStateEnum.class, id);
        if (taskState == CollectTaskStateEnum.IN_EXECUTE || taskState == CollectTaskStateEnum.NOT_START) {
            baseMapper.update(null, new UpdateWrapper<CollectTask>()
                    .set("state", CollectTaskStateEnum.PAUSE).set("pause_time", new Date()).eq("id", id));
            opsForValue.set(COLLECT_STATE_KEY + id, CollectTaskStateEnum.PAUSE.getValue());
            return true;
        }
        throw new ResultException(ResultEnum.TASK_STATE_ERROR);
    }

    /**
     * ????????????
     * @param id
     * @return
     */
    @Override
    public Boolean stopCollectTask(Integer id) {
        ValueOperations<String, Object> opsForValue = redisTemplate.opsForValue();
        CollectTaskStateEnum taskState = redisHelper.getCollectTaskState(CollectTaskStateEnum.class, id);
        if (taskState != CollectTaskStateEnum.FINISHED) {
            baseMapper.update(null, new UpdateWrapper<CollectTask>()
                    .set("state", CollectTaskStateEnum.STOP).set("stop_time", new Date()).eq("id", id));
            opsForValue.set(COLLECT_STATE_KEY + id, CollectTaskStateEnum.STOP.getValue());
            return true;
        }
        throw new ResultException(ResultEnum.TASK_STATE_ERROR);
    }

    /**
     * ??????????????????
     * @param id
     * @return
     */
    @Override
    public Integer getTaskProcess(Integer id) {
        ValueOperations<String, Object> opsForValue = redisTemplate.opsForValue();
        Object obj = opsForValue.get(COLLECT_PROCESS_KEY + id);
        if (obj != null) {
            String processStr = (String) obj;
            String[] pageAndCount = processStr.split(":");
            int page = Integer.parseInt(pageAndCount[0]);
            int count = Integer.parseInt(pageAndCount[1]);
            return page * 100 / count;
        }
        CollectTask collectTask = baseMapper.selectById(id);
        if (collectTask.getCurrentPage() == null) {
            return 0;
        }
        return collectTask.getCurrentPage() * 100 / collectTask.getTotalPage();
    }

    /**
     * ????????????????????????
     * @param dto
     * @return
     */
    @Override
    public Page<CollectTaskPageVO> pageCollectTask(CollectTaskPageDTO dto) {
        Page<CollectTaskPageVO> page = baseMapper.pageCollectTask(dto);
        List<CollectTaskPageVO> records = page.getRecords();
        for (CollectTaskPageVO record : records) {
            record.setProcess(getTaskProcess(record.getId()));
        }
        return page;
    }

}
