package br.com.phdigitalcode.azzo.assistant.llm;

import com.fasterxml.jackson.annotation.JsonProperty;

public class OllamaOptions {

    public Double temperature;

    @JsonProperty("num_predict")
    public Integer numPredict;

    public OllamaOptions() {}

    public OllamaOptions(double temperature, int numPredict) {
        this.temperature = temperature;
        this.numPredict = numPredict;
    }
}
