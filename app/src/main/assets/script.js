document.querySelectorAll(".project").forEach(button=>{

button.onclick=()=>{

document.querySelector(".active").classList.remove("active");

button.classList.add("active");

};

});
