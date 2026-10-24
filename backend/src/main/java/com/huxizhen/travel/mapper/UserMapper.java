package com.huxizhen.travel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.huxizhen.travel.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
