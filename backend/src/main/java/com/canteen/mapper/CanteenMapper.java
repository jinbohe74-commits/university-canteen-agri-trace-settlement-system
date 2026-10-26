package com.canteen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.canteen.entity.Canteen;
import org.apache.ibatis.annotations.Select;

public interface CanteenMapper extends BaseMapper<Canteen> {

    @Select("SELECT id FROM canteen WHERE user_id = #{userId} LIMIT 1")
    Long selectCanteenIdByUserId(Long userId);
}
