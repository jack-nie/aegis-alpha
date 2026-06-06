package com.aegis.alpha.mapper;

import com.aegis.alpha.domain.AgentTemplate;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AgentMapper {
    @Select("select agent_id, name, description, category, tags, prompt, model_name, tools_json, status, schedule_cron, last_run_at, input_count, output_count, tool_count, system_preset, readonly_flag, owner_username, sort_order, updated_at from agent_template order by sort_order asc, created_at asc")
    List<AgentTemplate> findAll();

    @Select("select agent_id, name, description, category, tags, prompt, model_name, tools_json, status, schedule_cron, last_run_at, input_count, output_count, tool_count, system_preset, readonly_flag, owner_username, sort_order, updated_at from agent_template where agent_id = #{agentId}")
    AgentTemplate findById(String agentId);

    @Select("select count(*) from agent_template")
    int count();

    @Insert("insert into agent_template(agent_id, name, description, category, tags, prompt, model_name, tools_json, status, schedule_cron, last_run_at, input_count, output_count, tool_count, system_preset, readonly_flag, owner_username, sort_order, updated_at) values(#{agentId}, #{name}, coalesce(#{description}, ''), coalesce(#{category}, 'analyst'), coalesce(#{tags}, ''), #{prompt}, coalesce(#{modelName}, 'deepseek-v4-flash'), #{toolsJson}, coalesce(#{status}, 'IDLE'), #{scheduleCron}, #{lastRunAt}, #{inputCount}, #{outputCount}, #{toolCount}, #{systemPreset}, #{readonlyFlag}, #{ownerUsername}, #{sortOrder}, coalesce(#{updatedAt}, ''))")
    void insert(AgentTemplate agent);

    @Update("update agent_template set name = #{name}, description = #{description}, category = #{category}, tags = #{tags}, prompt = #{prompt}, model_name = #{modelName}, tools_json = #{toolsJson}, status = #{status}, schedule_cron = #{scheduleCron}, input_count = #{inputCount}, output_count = #{outputCount}, tool_count = #{toolCount}, updated_at = #{updatedAt} where agent_id = #{agentId}")
    void update(AgentTemplate agent);

    @Update("update agent_template set last_run_at = #{lastRunAt}, status = #{status}, updated_at = #{updatedAt} where agent_id = #{agentId}")
    void updateRunState(AgentTemplate agent);

    @Delete("delete from agent_template where agent_id = #{agentId} and readonly_flag = 0")
    int deleteEditable(String agentId);

    @Select("select coalesce(max(sort_order), 0) + 10 from agent_template")
    int nextSortOrder();

    @Select("select count(*) from agent_template where owner_username = #{username} and name = #{name}")
    int countByOwnerAndName(@Param("username") String username, @Param("name") String name);
}
