<template>
  <div id="app">
    <ProxyConfigForm />
  </div>
</template>
<script>
  import ProxyConfigForm from './components/ProxyConfigForm.vue'

  export default {
    name: 'App',
    components: {
      ProxyConfigForm
    }
  }
</script>

<style>
#app {
  font-family: Avenir, Helvetica, Arial, sans-serif;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  text-align: center;
  color: #2c3e50;
  margin-top: 60px;
}
</style>
