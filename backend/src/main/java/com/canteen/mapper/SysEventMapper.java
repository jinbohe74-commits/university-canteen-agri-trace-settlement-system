package com.canteen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.canteen.entity.SysEvent;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface SysEventMapper extends BaseMapper<SysEvent> {

    @Select("SELECT COALESCE(MAX(id), 0) FROM sys_event")
    long maxId();

    @Select("SELECT * FROM sys_event WHERE id > #{since} ORDER BY id ASC LIMIT #{limit}")
    List<SysEvent> since(@org.apache.ibatis.annotations.Param("since") long since,
                         @org.apache.ibatis.annotations.Param("limit") int limit);

    @Select("SELECT * FROM sys_event ORDER BY id DESC LIMIT #{limit}")
    List<SysEvent> latest(@org.apache.ibatis.annotations.Param("limit") int limit);
}
