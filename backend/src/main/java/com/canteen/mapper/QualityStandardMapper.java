package com.canteen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.canteen.entity.QualityStandard;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface QualityStandardMapper extends BaseMapper<QualityStandard> {

    /**
     * 按一级品类取检测项。商品档案挂的是二级品类（如「白萝卜」），
     * 这里直接按传入的一级品类查，避免每次都在 SQL 里做两级回溯。
     */
    @Select("SELECT * FROM quality_standard WHERE category_id = #{categoryId} ORDER BY sort, id")
    List<QualityStandard> listByCategory(@Param("categoryId") Long categoryId);
}
