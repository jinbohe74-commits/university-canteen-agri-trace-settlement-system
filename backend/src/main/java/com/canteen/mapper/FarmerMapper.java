package com.canteen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.canteen.entity.Farmer;
import org.apache.ibatis.annotations.Select;

public interface FarmerMapper extends BaseMapper<Farmer> {

    @Select("SELECT id FROM farmer WHERE user_id = #{userId} LIMIT 1")
    Long selectFarmerIdByUserId(Long userId);
}
