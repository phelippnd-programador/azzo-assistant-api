package br.com.phdigitalcode.azzo.assistant.infrastructure.client.dto;

/**
 * Horário de funcionamento de um dia da semana do salão.
 * Espelha {@code SalonDtos.BusinessHour} do azzo-agenda-pro.
 */
public class HorarioFuncionamentoDto {

    /** Nome do dia em português, ex.: "Segunda-feira", "Sabado", "Domingo". */
    public String day;

    /** {@code true} = salão abre neste dia; {@code false} = fechado. */
    public boolean enabled;

    /** Horário de abertura, ex.: "09:00". Ignorado se {@code enabled = false}. */
    public String open;

    /** Horário de fechamento, ex.: "19:00". Ignorado se {@code enabled = false}. */
    public String close;
}
